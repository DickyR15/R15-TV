# Third-party notices for the R15 TV subtitle proof of concept

This experimental build includes the following third-party components:

- **sherpa-onnx** streaming speech recognition runtime and the bilingual Zipformer model. The runtime license and upstream model README are packaged under `assets/ai-subtitle-model/` in the APK. Check the model README separately from the runtime license before redistribution.
- **Google ML Kit Translation** (`com.google.mlkit:translate:17.0.3`). This is Google's on-device translation SDK, not an open-source model. The first-use model download is managed by the ML Kit/Google Play services runtime. No paid translation API key is configured by this prototype. See the [official Android translation documentation](https://developers.google.com/ml-kit/language/translation/android?hl=zh-tw).
- **Android OpenCC** (`com.github.qichuan:android-opencc:1.2.0`) and upstream OpenCC. The Android wrapper MIT license and upstream Apache-2.0 license are included alongside the build artifact. OpenCC performs script/region conversion after translation; it is not the English-to-Chinese translation engine.
- **FongMi/TV and FongMi/media** upstream code and libraries remain subject to their own GPL-3.0 and third-party license notices.

This is a development test build, not a public stable release. Re-check the exact dependency versions, model card/license, and upstream notices before redistribution.
