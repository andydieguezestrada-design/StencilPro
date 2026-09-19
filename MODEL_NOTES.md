# StencilPro AI line-art engine

This build uses the ONNX image-to-line-drawing models from `Luoaho/image-to-line-drawing-onnx` as the learned line-art stage. The model repository is Apache-2.0 licensed and publishes `line-drawings.onnx` (17.2 MB) and `line-relifer.onnx` (1.19 MB).

The Android app downloads these files on first AI processing into app-private storage and reuses them thereafter. Photos are not uploaded by the engine. The app then applies stencil-specific structural filtering, component cleanup, line bridging, thinning and line-color rendering.

Reference: https://huggingface.co/Luoaho/image-to-line-drawing-onnx

Important: the upstream model is described by its authors as particularly suitable for anime/line-art images. It is therefore used here as the learned line-art backbone, while the post-processing is specialized for tattoo stencil preparation. A future dedicated tattoo-stencil model can replace this backbone without changing the Android UI/API.

The larger ControlNet/MistoLine families were evaluated as references. ControlNet 1.1 Lineart is 0.4B parameters and is intended to condition Stable Diffusion; MistoLine is an SDXL ControlNet and its main checkpoint is multi-gigabyte. Those are better suited to a full generative pipeline than a first embedded Android line-extraction stage.
