本文介绍 Qwen-Omni-Realtime API 的客户端事件。

> 相关文档：[实时（Qwen-Omni-Realtime）](https://help.aliyun.com/zh/model-studio/realtime)。

## **session.update**

客户端建立 WebSocket 连接后，需首先发送该事件，用于更新会话的默认配置。服务端收到 `session.update` 事件后会校验参数。如果参数不合法，则返回错误；如果参数合法，则更新并返回完整的配置。

| **type** `*string*` **(必选)** 事件类型，固定为`session.update`。 | ``` { "event_id": "event_ToPZqeobitzUJnt3QqtWg", "type": "session.update", "session": { "modalities": [ "text", "audio" ], "voice": "Chelsie", "input_audio_format": "pcm", "output_audio_format": "pcm", "instructions": "你是某五星级酒店的AI客服专员，请准确且友好地解答客户关于房型、设施、价格、预订政策的咨询。请始终以专业和乐于助人的态度回应，杜绝提供未经证实或超出酒店服务范围的信息。", "turn_detection": { "type": "server_vad", "threshold": 0.5, "silence_duration_ms": 800 }, "enable_search": true, "search_options": { "enable_source": true }, "tools": [ { "type": "function", "function": { "name": "get_current_weather", "description": "当你想查询指定城市的天气时非常有用。", "parameters": { "type": "object", "properties": { "location": { "type": "string", "description": "城市或县区，比如北京市、杭州市、余杭区等。" } }, "required": ["location"] } } } ], "seed": 1314, "max_tokens": 16384, "repetition_penalty": 1.05, "presence_penalty": 0.0, "top_k": 50, "top_p": 1.0, "temperature": 0.9 } } ``` |
| --- | --- |
| **session** `*object*` （可选） 会话配置。 **属性** **modalities** `*array*` （可选） 模型输出模态设置，可选值： - \\["text"\\] 仅输出文本。 - \\["text","audio"\\]（默认值） 输出文本与音频。 **voice** `*string*` （可选） 模型生成音频的音色，支持的音色参见[音色列表](https://help.aliyun.com/zh/model-studio/realtime#f9c68d860a3rs)。 默认音色： - Qwen3.5-Omni-Realtime系列：`Tina` - Qwen3-Omni-Flash-Realtime：`Cherry` - Qwen-Omni-Turbo-Realtime：`Chelsie` **input\\_audio\\_format** `*string*` （可选） 用户输入音频格式，当前仅支持设为`pcm`。 **output\\_audio\\_format** `*string*` （可选） 模型输出音频的格式，当前仅支持设为`pcm`。 **instructions** `*string*` （可选） 系统消息，用于设定模型的目标或角色。 **turn\\_detection** `*object*` （可选） 语音活动检测（VAD）的配置。设置为 `null` 表示禁用 VAD，由用户手动触发模型响应。若未提供本字段，系统将使用以下默认参数启用 VAD。 **属性** **type** `*string*` （可选） VAD类型，取值如下： - `server_vad`（默认值）：基于声学特征检测用户语音结束。 - `semantic_vad`：基于语义有效性检测用户语音结束，可过滤无意义语音（如回应语、背景音）。仅`qwen3.5-omni-realtime`模型支持。 **threshold** `*float*` （可选） VAD的灵敏度。值越低，VAD越敏感，更容易将微弱声音（包括背景噪音）识别为语音；值越高，越不敏感，需要更清晰、音量更大的语音才能触发。 取值范围在`[-1.0, 1.0]`，默认值为 0.5。 **silence\\_duration\\_ms** `*integer*` （可选） 语音结束后需保持静音的最短时间，超时即触发模型响应。值越低，响应越快，但可能在语音短暂停顿时误触发模型响应。 默认值为800，参数范围\\[200, 6000\\]。 **enable\\_search** `*boolean*` （可选） **仅在使用 Qwen3.5-Omni-Realtime 模型时生效。** 是否启用联网搜索功能。设置为 `true` 启用，默认为 `false`。启用后，模型可自主判断是否需要搜索来回应用户的即时问题。 > 工具调用（tools）和联网搜索（enable\\_search）不兼容，不可同时开启。 **search\\_options** `*object*` （可选） 联网搜索选项配置。需启用 `enable_search` 后才生效。 **属性** **enable\\_source** `*boolean*` （可选） 是否返回搜索结果来源列表。设置为 `true` 启用。 **tools** `*array*` （可选） 工具定义列表。配置后模型可根据用户输入自主决定是否调用工具。 **属性** **type** `*string*`（必选） 固定为 `function`。 **function.name** `*string*`（必选） 自定义的工具函数名称，建议使用与函数相同的名称，如`get_current_weather`或`get_current_time`。 **function.description** `*string*`（可选） 对工具函数功能的描述，大模型会参考该字段来选择是否使用该工具函数。 **function.parameters** `*object*`（可选） 对工具函数入参的描述，大模型会参考该字段来进行入参的提取。如果工具函数不需要输入参数，则无需指定。 **属性** **type** `*string*`（必选） 固定为 `object`。 **properties** `*object*`（可选） 描述各入参的名称、数据类型与描述。Key 值为入参的名称，Value 值为包含数据类型（`type`）与描述（`description`）的对象。 **required** `*array*`（可选） 指定哪些入参为必填项。 |
| **temperature** `*float*` （可选） 采样温度，控制模型生成内容的多样性。 temperature越高，生成的内容更多样，反之，生成的内容更确定。 取值范围： \\[0, 2) 由于temperature与top\\_p均可以控制生成内容的多样性，因此建议您只设置其中一个值。 temperature默认值： - `qwen3.5-omni-realtime`系列：0.7 - `qwen3-omni-flash-realtime`系列：0.9 - `qwen-omni-turbo-realtime`系列：1.0 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **top\\_p** `*float*` （可选） 核采样的概率阈值，控制模型生成内容的多样性。 top\\_p越高，生成的内容更多样。反之，生成的内容更确定。 取值范围：（0,1.0\\] 由于temperature与top\\_p均可以控制生成内容的多样性，因此建议您只设置其中一个值。 top\\_p默认值： - `qwen3.5-omni-realtime`系列：0.8 - `qwen3-omni-flash-realtime`系列：1.0 - `qwen-omni-turbo-realtime`系列：0.01 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **top\\_k** `*integer*` （可选） 生成过程中采样候选集的大小。例如，取值为50时，仅将单次生成中得分最高的50个Token组成随机采样的候选集。取值越大，生成的随机性越高；取值越小，生成的确定性越高。取值为`null`或当top\\_k大于100时，表示不启用`top_k`策略，此时仅有`top_p`策略生效。 取值需要大于或等于0。 top\\_k默认值： - `qwen3.5-omni-realtime`系列：20 - `qwen3-omni-flash-realtime`系列：50 - `qwen-omni-turbo-realtime`系列：20 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **max\\_tokens** `*integer*` （可选） 本次请求返回的最大 Token 数。 > `max_tokens` 的设置不会影响大模型的生成过程，如果模型生成的 Token 数超过`max_tokens`，本次请求会返回截断后的内容。 默认值和最大值都是模型的最大输出长度。关于各模型的最大输出长度，请参见[模型列表](https://help.aliyun.com/zh/model-studio/models#9f8890ce29g5u)。 max\\_tokens参数适用于需要限制字数（如生成摘要、关键词）、控制成本或减少响应时间的场景。 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **repetition\\_penalty** `*float*` （可选） 模型生成时连续序列中的重复度。提高repetition\\_penalty时可以降低模型生成的重复度，1.0表示不做惩罚。没有严格的取值范围，只要大于0即可。 repetition\\_penalty默认值： - `qwen3.5-omni-realtime`系列：1.0 - `qwen3-omni-flash-realtime`系列：1.05 - `qwen-omni-turbo-realtime`系列：1.05 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **presence\\_penalty** `*float*` （可选） 控制模型生成内容时的重复度。 取值范围：\\[-2.0, 2.0\\]。正数会减少重复度，负数会增加重复度。 presence\\_penalty默认值： - `qwen3.5-omni-realtime`系列：1.5 - `qwen3-omni-flash-realtime`系列：0.0 - `qwen-omni-turbo-realtime`系列：0.0 适用场景： 较高的presence\\_penalty适用于要求多样性、趣味性或创造性的场景，如创意写作或头脑风暴。 较低的presence\\_penalty适用于要求一致性或专业术语的场景，如技术文档或其他正式文档。 > `qwen-omni-turbo` 系列模型**不支持修改**。 |
| **seed** `*integer*` （可选） 设置seed参数会使大模型的生成过程更具有确定性，通常用于使模型每次运行的结果一致。 在每次模型调用时传入相同的seed值（由您指定），并保持其他参数不变，模型将尽可能返回相同的结果。 取值范围：0到231−1，默认值-1。 > `qwen-omni-turbo` 系列模型**不支持修改**。 |

## **response.create**

`response.create` 事件用于指示服务端创建模型响应。在VAD模式下，服务端会自动创建模型响应，无需发送该事件。在工具调用场景中，客户端通过 `conversation.item.create` 回传工具结果后，需发送此事件触发模型生成最终响应。

服务端使用 `response.created` 事件、一个或多个项和内容事件（如 `conversation.item.created` 和 `response.content_part.added`）进行响应，最后用一个 `response.done` 事件表示响应已完成。

| **type** `*string*` **（必选）** 事件类型，固定为`response.create`。 | ``` { "type": "response.create", "event_id": "event_1718624400000" } ``` |
| --- | --- |

## **response.cancel**

客户端发送此事件用以取消正在进行的响应。如果没有任何响应可供取消，服务端将响应错误事件。

| **type** `*string*` **(必选)** 事件类型，固定为`response.cancel`。 | ``` { "event_id": "event_B4o9RHSTWobB5OQdEHLTo", "type": "response.cancel" } ``` |
| --- | --- |

## **input\_audio\_buffer.append**

用于将音频字节追加到输入音频缓冲区。

| **type** `*string*` **(必选)** 事件类型，固定为`input_audio_buffer.append`。 | ``` { "event_id": "event_B4o9RHSTWobB5OQdEHLTo", "type": "input_audio_buffer.append", "audio": "UklGR..." } ``` |
| --- | --- |
| **audio** `*string*` **(必选)** Base64 编码的音频数据。 |

## **input\_audio\_buffer.commit**

用于提交用户输入音频缓冲区，在对话中创建新的用户消息项。 如果输入的音频缓冲区为空，服务端会返回错误事件。

-   [VAD 模式](https://help.aliyun.com/zh/model-studio/realtime#68d826b358q1r)：客户端不需要发送此事件，服务端会自动提交音频缓冲区。
    
-   [Manual 模式](https://help.aliyun.com/zh/model-studio/realtime#3dbb650fb3ird)：客户端必须提交音频缓冲区才能创建用户消息项。
    

提交输入音频缓冲区不会从模型创建响应，服务端将使用 `input_audio_buffer.committed` 事件进行响应。

> 如果客户端发送过[input\_image\_buffer.append](#c28ed38410nfw)事件，input\_audio\_buffer.commit 事件会将图像缓冲区一起提交。

| **type** `*string*` **(必选)** 事件类型，固定为`input_audio_buffer.commit`。 | ``` { "event_id": "event_B4o9RHSTWobB5OQdEHLTo", "type": "input_audio_buffer.commit" } ``` |
| --- | --- |

## **input\_audio\_buffer.clear**

用于清除缓冲区中的音频字节。服务端发送`input_audio_buffer.cleared` 事件进行响应。

| **type** `*string*` **(必选)** 事件类型，固定为`input_audio_buffer.clear`。 | ``` { "event_id": "event_xxx", "type": "input_audio_buffer.clear" } ``` |
| --- | --- |

## **input\_image\_buffer.append**

用于将图像数据添加到图像缓冲区。图像可来自本地文件，或从视频流实时采集。

目前对图片输入有以下限制：

-   图像格式必须为 JPG 或 JPEG。建议分辨率为 480p 或 720p以获得最佳性能，最高不超过 1080p；
    
-   单张图片大小不大于500KB（Base64编码前）；
    
-   图片数据需要经过Base64编码；
    
-   建议以 1张/秒 的频率向服务端发送图像；
    
-   发送 input\_image\_buffer.append 事件前，至少发送过一次 input\_audio\_buffer.append 事件。
    

> 图像缓冲区与音频缓冲区一起通过[input\_audio\_buffer.commit](#1cbea5fa7fkfl)事件提交。

| **type** `*string*` **(必选)** 事件类型，固定为`input_image_buffer.append`。 | ``` { "event_id": "event_xxx", "type": "input_image_buffer.append", "image": "xxx" } ``` |
| --- | --- |
| **image** `*string*` **(必选)** Base64 编码的图像数据。 |

## **conversation.item.create**

客户端发送此事件，将工具函数的执行结果回传给服务端。当模型触发工具调用后，客户端需在本地执行工具函数，然后通过此事件将结果发回，再发送 `response.create` 触发模型生成最终响应。

**说明**

当前仅支持 `function_call_output` 类型的 item。

| **type** `*string*` **(必选)** 事件类型，固定为`conversation.item.create`。 | ``` { "event_id": "event_55099cddb51b4f208cb95d1a994eef80", "type": "conversation.item.create", "item": { "id": "item_2a80d7682b4e473c9c2154da135041e9", "type": "function_call_output", "call_id": "call_62c24725afdb4c2680ac54", "output": "北京今天天气为霾转晴，气温4/-4℃，微风" } } ``` |
| --- | --- |
| **item** `*object*` **(必选)** 要创建的对话项，不能为空。 **属性** **id** `*string*`（可选） 对话项 ID。客户端可预先指定以便对齐本地状态；若未提供，由服务端生成。 **type** `*string*`（必选） 对话项类型。当前仅支持 `function_call_output`。 **call\\_id** `*string*`（必选） 对应 `response.function_call_arguments.done` 事件中返回的 `call_id`。 **output** `*string*`（必选） 工具函数的执行结果。 |