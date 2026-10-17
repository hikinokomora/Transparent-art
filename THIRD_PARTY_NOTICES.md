# Сторонние компоненты

## Informative Drawings

Модели линейного рисунка в `app/src/main/assets/models/` получены из весов проекта
[Informative Drawings: Learning to Generate Line Drawings that Convey Geometry and Semantics](https://github.com/carolineec/informative-drawings)
(Caroline Chan, Frédo Durand, Phillip Isola, CVPR 2022). Веса переведены в формат ONNX
скриптом `tools/export_informative_drawings.py` без изменения самой модели.

Лицензия MIT, Copyright (c) 2022 Caroline Chan. Полный текст: [`app/src/main/assets/models/LICENSE-informative-drawings.txt`](app/src/main/assets/models/LICENSE-informative-drawings.txt).

## ONNX Runtime

[ONNX Runtime](https://github.com/microsoft/onnxruntime) запускает модели на телефоне.
Лицензия MIT, Copyright (c) Microsoft Corporation.

## AndroidX, Jetpack Compose, CameraX

Библиотеки Android Open Source Project. Лицензия Apache 2.0.
