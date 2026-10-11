# Third-party notices for the R15 TV subtitle proof of concept

This experimental build includes the following third-party components:

- **Whisper.cpp** (ggml-org/whisper.cpp), pinned to commit 7e26d3b0d5bced6c8217ef09710ec2f969001241, provides the local speech-recognition engine. Its MIT license is packaged with the test artifact. GGML is the supporting inference backend included in the same upstream source tree.
- **Whisper Base multilingual model** (ggml-base.bin) is downloaded by the user from the upstream model repository on first use. Model weights are not bundled in the APK. The model file is converted from OpenAI's original Whisper Base multilingual checkpoint; see the upstream [model-file documentation](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md) and [OpenAI Whisper license](https://github.com/openai/whisper/blob/main/LICENSE). The model download and model-weight terms are distinct from the code license.
- **Google ML Kit Translation** (com.google.mlkit:translate:17.0.3) provides on-device English-to-Chinese translation. The first-use translation model download is managed by the ML Kit / Google Play services runtime. No paid translation API key is configured. See the [official Android translation documentation](https://developers.google.com/ml-kit/language/translation/android?hl=zh-tw).
- **Android OpenCC** (com.github.qichuan:android-opencc:1.2.0) and upstream OpenCC perform script and region conversion after translation. The Android wrapper MIT license and upstream Apache-2.0 license are included alongside the build artifact. OpenCC is not the English-to-Chinese translation engine.
- **FongMi/TV and FongMi/media** upstream code and libraries remain subject to their own GPL-3.0 and third-party license notices.

This is a development test build, not a stable release. The test build only packages the Whisper.cpp native runtime; the Whisper Base model must be downloaded inside the app. First-use downloads need internet access. Speech recognition and translation are intended to run locally after the relevant model files have been downloaded.
