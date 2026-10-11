# Third-party notices for the R15 TV subtitle proof of concept

This experimental build includes the following third-party components:

- **sherpa-onnx** streaming speech recognition runtime and the small bilingual Zipformer model. The model documentation identifies its source as [csukuangfj/k2fsa-zipformer-bilingual-zh-en-t](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t), whose model card labels it Apache-2.0. The APK packages the sherpa-onnx runtime license, the Apache-2.0 text, model README and a provenance/license notice under `assets/ai-subtitle-model/`.
- **Google ML Kit Translation** (`com.google.mlkit:translate:17.0.3`). This is Google's on-device translation SDK, not an open-source model. The first-use model download is managed by the ML Kit/Google Play services runtime. No paid translation API key is configured by this prototype. See the [official Android translation documentation](https://developers.google.com/ml-kit/language/translation/android?hl=zh-tw).
- **Android OpenCC** (`com.github.qichuan:android-opencc:1.2.0`) and upstream OpenCC. The Android wrapper MIT license and upstream Apache-2.0 license are included alongside the build artifact. OpenCC performs script/region conversion after translation; it is not the English-to-Chinese translation engine.
- **FongMi/TV and FongMi/media** upstream code and libraries remain subject to their own GPL-3.0 and third-party license notices.

This is a development test build, not a public stable release. Re-check the exact dependency versions, model card/license, and upstream notices before redistribution.
