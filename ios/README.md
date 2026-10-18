# Transparent Art для iOS

Версия приложения для iPhone на Swift и SwiftUI с теми же возможностями, что и на Android: режим «Нейросеть» (модель Informative Drawings через ONNX Runtime, обе модели общие с Android и лежат в `../app/src/main/assets/models/`), классический фильтр, наложение эскиза на камеру, жесты, замок, зеркало, фонарик. Работает офлайн, доступа к интернету не просит.

Статус: **экспериментальная**. Код написан без доступа к Xcode и проверяется только сборкой и тестами на macOS-раннере GitHub Actions (`.github/workflows/ios.yml`); на реальном iPhone ещё не запускался.

## Требования

- iOS 16 или новее, только iPhone, только портретная ориентация
- Для сборки: Mac с Xcode 16 и [XcodeGen](https://github.com/yonaskolb/XcodeGen)

## Сборка

```bash
brew install xcodegen
cd ios
xcodegen generate
open TransparentArt.xcodeproj
```

В Xcode выберите свою команду разработчика в *Signing & Capabilities* и запустите на симуляторе или устройстве. Файл `.xcodeproj` в репозитории не хранится: он собирается из `project.yml`.

Тесты из командной строки:

```bash
xcodebuild test -project TransparentArt.xcodeproj -scheme TransparentArt \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

## Установка без App Store

CI собирает неподписанный `TransparentArt-unsigned.ipa` и кладёт его в артефакты запуска (*Actions → iOS → Artifacts*). Такой файл нельзя просто открыть на iPhone: его нужно подписать своим Apple ID, например через [AltStore](https://altstore.io) или [Sideloadly](https://sideloadly.io). С бесплатным Apple ID подпись действует 7 дней, потом приложение переустанавливают. Постоянная установка и TestFlight/App Store требуют платной программы Apple Developer.

## Устройство

| Файл | Что делает |
| --- | --- |
| `Core/SketchEngine.swift`, `Core/Grid.swift` | Классический фильтр, порт `SketchEngine.kt` без зависимостей |
| `Core/NeuralSketcher.swift` | Запуск моделей через ONNX Runtime |
| `Core/SketchProcessor.swift` | Выбор режима, обработка линий нейросети, сборка `CGImage` |
| `Core/ImageLoader.swift` | Загрузка фото с поворотом по EXIF и масштабированием |
| `App/AppModel.swift` | Состояние приложения и фоновая обработка |
| `Views/EditorView.swift` | Выбор фото и настройки эскиза |
| `Views/TraceView.swift`, `Views/CameraController.swift` | Камера с наложением, жесты |
| `Resources/*.lproj` | Строки на английском и русском, сгенерированы из Android-ресурсов |

Классический фильтр на Swift повторяет Android-версию построчно. Если меняете алгоритм, меняйте обе реализации.
