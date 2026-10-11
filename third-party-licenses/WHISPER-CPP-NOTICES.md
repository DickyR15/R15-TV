# Whisper.cpp and model notices

- **Runtime source:** [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp), pinned to commit 7e26d3b0d5bced6c8217ef09710ec2f969001241.
- **Runtime license:** MIT. The exact upstream LICENSE file is copied into the experimental build artifact.
- **Model:** ggml-base.bin, multilingual Whisper Base weights. The APK does not contain the model; the user downloads it from [the upstream model repository](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin) inside the app.
- **Model provenance:** The upstream [whisper.cpp model documentation](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md) states that its GGML model files are converted from OpenAI's original Whisper models. Runtime source code and model weights are separate artifacts; review current upstream notices before any public release.
- **No model weights are redistributed by the APK build workflow.** The test build packages the native runtime only.
