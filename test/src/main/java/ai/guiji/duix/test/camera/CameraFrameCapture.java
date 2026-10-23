package ai.guiji.duix.test.camera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前置摄像头帧采集，按约 1 张/秒 输出 JPEG 供 OmniRealtime 使用
 * 推荐 480P/720P，单张不超过 500KB
 */
public class CameraFrameCapture {

    private static final String TAG = "CameraFrameCapture";
    private static final int TARGET_WIDTH = 640;   // 480P 左右
    private static final int TARGET_HEIGHT = 480;
    private static final int JPEG_QUALITY = 85;
    private static final long CAPTURE_INTERVAL_MS = 1000; // 1 张/秒

    private final Context context;
    private final FrameCallback callback;
    private final TextureView previewView;
    private Surface previewSurface;
    private CameraManager cameraManager;
    private String frontCameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            captureStillFrame();
        }
    };

    public CameraFrameCapture(Context context, FrameCallback callback) {
        this(context, callback, null);
    }

    public CameraFrameCapture(Context context, FrameCallback callback, TextureView previewView) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.previewView = previewView;
    }

    public void start() {
        if (isRunning.get()) {
            Log.w(TAG, "Already running");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) callback.onError("需要相机权限");
            return;
        }
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            if (callback != null) callback.onError("相机不可用");
            return;
        }
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id;
                    break;
                }
            }
            if (frontCameraId == null) {
                if (callback != null) callback.onError("未找到前置摄像头");
                return;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
            if (callback != null) callback.onError("相机访问失败: " + e.getMessage());
            return;
        }

        backgroundThread = new HandlerThread("CameraFrameCapture");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        imageReader = ImageReader.newInstance(TARGET_WIDTH, TARGET_HEIGHT, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) {
                if (image != null && callback != null) {
                    byte[] jpeg = imageToJpeg(image);
                    if (jpeg != null && jpeg.length > 0 && jpeg.length < 500 * 1024) {
                        callback.onFrame(jpeg);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Image process error: " + e.getMessage());
            }
        }, backgroundHandler);

        isRunning.set(true);
        if (previewView != null) {
            if (previewView.isAvailable()) {
                setupPreviewSurface();
                openCamera();
            } else {
                previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                        setupPreviewSurface();
                        openCamera();
                    }
                    @Override
                    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {}
                    @Override
                    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                        return true;
                    }
                    @Override
                    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
                });
            }
        } else {
            openCamera();
        }
    }

    private void setupPreviewSurface() {
        if (previewView != null && previewView.getSurfaceTexture() != null) {
            android.graphics.SurfaceTexture st = previewView.getSurfaceTexture();
            st.setDefaultBufferSize(TARGET_WIDTH, TARGET_HEIGHT);
            previewSurface = new Surface(st);
        }
    }

    private void openCamera() {
        try {
            cameraManager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    if (callback != null) callback.onError("相机打开失败");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Open camera error: " + e.getMessage());
            if (callback != null) callback.onError("相机打开失败: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Camera permission denied: " + e.getMessage());
            if (callback != null) callback.onError("需要相机权限");
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null || !isRunning.get()) return;
        try {
            java.util.List<Surface> surfaces = new java.util.ArrayList<>();
            surfaces.add(imageReader.getSurface());
            if (previewSurface != null) {
                surfaces.add(previewSurface);
            }
            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            isCapturing.set(true);
                            startPreview();
                            scheduleNextCapture(0L);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                            if (callback != null) callback.onError("相机配置失败");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Create session error: " + e.getMessage());
        }
    }

    private void startPreview() {
        if (captureSession == null || cameraDevice == null || previewSurface == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Start preview error: " + e.getMessage());
        }
    }

    private void scheduleNextCapture(long delayMs) {
        if (backgroundHandler == null) return;
        backgroundHandler.removeCallbacks(captureRunnable);
        backgroundHandler.postDelayed(captureRunnable, Math.max(delayMs, 0L));
    }

    private void captureStillFrame() {
        if (!isCapturing.get() || !isRunning.get() || captureSession == null || cameraDevice == null || imageReader == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            CaptureRequest request = builder.build();
            captureSession.capture(request, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull android.hardware.camera2.CaptureRequest request,
                        @NonNull android.hardware.camera2.TotalCaptureResult result) {
                    if (isRunning.get()) {
                        scheduleNextCapture(CAPTURE_INTERVAL_MS);
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Capture error: " + e.getMessage());
        }
    }

    public void requestImmediateCapture() {
        if (!isRunning.get() || !isCapturing.get()) {
            return;
        }
        scheduleNextCapture(0L);
    }

    private byte[] imageToJpeg(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    public void stop() {
        isRunning.set(false);
        isCapturing.set(false);
        try {
            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacks(captureRunnable);
            }
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
        if (backgroundThread != null && backgroundThread.isAlive()) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500);
            } catch (InterruptedException ignored) {
            }
            backgroundThread = null;
        }
        backgroundHandler = null;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public interface FrameCallback {
        void onFrame(byte[] jpegData);

        void onError(String message);
    }
}
