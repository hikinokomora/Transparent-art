# Transparent Art

[![Build](https://github.com/hikinokomora/Transparent-art/actions/workflows/build.yml/badge.svg)](https://github.com/hikinokomora/Transparent-art/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/hikinokomora/Transparent-art)](https://github.com/hikinokomora/Transparent-art/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

Бесплатное приложение для Android с открытым исходным кодом, которое помогает перерисовать любую картинку на бумагу.

1. Выберите фото в галерее. Приложение превратит его в эскиз для карандаша: выделит основные контуры и добавит лёгкую ступенчатую штриховку.
2. Настройте детализацию, толщину линий, штриховку и цвет линий. Удерживайте превью, чтобы сравнить с оригиналом.
3. Нажмите «Рисовать». Эскиз ляжет полупрозрачным слоем поверх камеры. Поставьте телефон на подставку (стопка книг, стакан) в 20–30 см над листом, подгоните картинку жестами, закрепите замком и обводите.

Ни рекламы, ни аккаунтов, ни сети: приложение не запрашивает доступ к интернету.

## Скачать

Готовый APK лежит на странице [Releases](https://github.com/hikinokomora/Transparent-art/releases/latest). Скачайте `transparent-art-*.apk` на телефон и откройте его; Android попросит разрешить установку из этого источника.

## Возможности

- Эскиз из фото: контуры (разность гауссиан) и 2–5 тонов штриховки, всё настраивается
- Наложение на камеру с регулируемой прозрачностью
- Масштаб, перемещение и поворот картинки двумя пальцами
- Замок, чтобы случайно не сдвинуть картинку во время рисования
- Зеркальное отражение, фонарик, быстрое скрытие картинки
- Экран не гаснет, пока вы рисуете
- Интерфейс на русском и английском

## Советы

- Чем выше телефон над листом, тем меньше искажений по краям. Камеру лучше направлять на лист строго сверху.
- Если линии сливаются с карандашом на экране, смените цвет линий на красный или синий.
- Кнопка с глазом быстро скрывает картинку: удобно, чтобы посмотреть на свой рисунок без подсказки.

## Сборка

Нужны [Android Studio](https://developer.android.com/studio) (Ladybug или новее) или Android SDK и JDK 17+.

```bash
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`.

Для подписанной release-сборки положите в корень проекта файл `keystore.properties`:

```properties
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

и выполните `./gradlew assembleRelease`. Без этого файла release-сборка получится неподписанной.

## Устройство проекта

| Файл | Что делает |
| --- | --- |
| `SketchProcessor.kt` | Фильтр фото → эскиз, чистый Kotlin без OpenCV |
| `ImageLoader.kt` | Загрузка фото из галереи с уменьшением и поворотом по EXIF |
| `EditorScreen.kt` | Выбор фото и настройка фильтра |
| `TraceScreen.kt` | Наложение эскиза на камеру и управление жестами |
| `CameraPreview.kt` | Предпросмотр камеры через CameraX |

## Участие

Ошибки, идеи и pull request'ы приветствуются, подробнее в [CONTRIBUTING.md](CONTRIBUTING.md).

## Лицензия

[MIT](LICENSE)
