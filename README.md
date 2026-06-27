<div align="center">

# RIN

A private, on-device AI assistant for Android.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build APK](https://github.com/Loke-60000/RIN/actions/workflows/build_android.yaml/badge.svg)](https://github.com/Loke-60000/RIN/actions/workflows/build_android.yaml)
![Platform](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)

</div>

RIN runs open large language models directly on your phone. It works fully offline with no account, and nothing leaves your device unless you choose to connect a cloud model. Chats and memory are stored locally, never on a server.

## What it does

- Chat offline with on-device models, or connect a cloud model (OpenAI, Anthropic, Gemini, OpenRouter, Ollama, or any OpenAI-compatible endpoint). API keys stay on the device.
- Send images to vision-capable models.
- Talk to it with on-device speech-to-text, and have it read replies aloud with local voices.
- Summon it from the power button as your device assistant, answering over any screen.
- Transcribe audio and translate text as one-shot tools from the drawer.
- Ground answers with web search (DuckDuckGo, a self-hosted SearXNG, or Ollama).
- Pull relevant pieces from your saved notes into context with on-device memory (RAG).
- Theme the whole UI live, with white, dark, or beige backgrounds, in 15 languages.
- Ghost mode pauses memory and stops saving chats for a private session.

## Download

Get the latest RIN.apk from the [Releases](https://github.com/Loke-60000/RIN/releases) page. Requires Android 12 (API 31) or newer. On first launch, open Settings, then Models, then Language models, and download a model to start.

## Build from source

```bash
cd Android/src
export JAVA_HOME=/path/to/jdk-21          # for example Android Studio's bundled JBR
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
```

Requires JDK 21 and the Android SDK with platform android-37.

## Built with

LiteRT-LM and TensorFlow Lite for on-device inference, Gemma and other open models, sherpa-onnx with Piper voices for text-to-speech, Vosk for speech recognition, ONNX Runtime for the RAG embedder, and Jetpack Compose for the UI. Inspired by [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery).

## License

[MIT](LICENSE).
