# DeletedMessages — плагин для Exteragram

Сохраняет удалённые сообщения и показывает их прямо в чате.

## Структура репозитория

```
deleted-messages/
├── .github/
│   └── workflows/
│       └── build.yml          ← GitHub сам собирает DEX
├── app/
│   ├── build.gradle
│   ├── libs/                  ← создаётся автоматически
│   └── src/main/java/ni/deleted/messages/
│       └── Main.kt            ← вся логика на Kotlin
├── outputs/                   ← сюда GitHub кладёт готовый .dex
│   ├── deleted-messages.dex
│   └── actual.json
├── build.gradle
├── settings.gradle
├── gradlew                    ← нужен для сборки (см. ниже)
├── gradlew.bat
└── DeletedMessages.plugin     ← устанавливается в Exteragram
```

---

## Пошаговая инструкция — выложить на GitHub

### Шаг 1 — Создать репозиторий

1. Открой https://github.com/new
2. Название: `deleted-messages`
3. Visibility: **Public** (обязательно, иначе Exteragram не скачает DEX)
4. Нажми **Create repository**

---

### Шаг 2 — Загрузить файлы

Самый простой способ — через браузер:

1. На странице репозитория нажми **Add file → Upload files**
2. Загрузи все файлы **сохраняя папки** (перетащи всю папку `deleted-messages` целиком)

Либо через Git (если установлен):
```bash
git clone https://github.com/ТВО_ИМЯ/deleted-messages.git
# скопируй все файлы в папку
git add .
git commit -m "Initial commit"
git push
```

---

### Шаг 3 — Добавить gradlew (ОБЯЗАТЕЛЬНО)

GitHub Actions использует `gradlew` для сборки. Его нужно добавить один раз:

1. Открой https://github.com/gradle/gradle/blob/master/gradlew — нажми **Raw**, скопируй всё
2. В своём репозитории: **Add file → Create new file**
3. Имя файла: `gradlew`, вставь содержимое, сохрани

Или скачай готовый: https://github.com/gradle/gradle/raw/master/gradlew

Также нужен `gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

---

### Шаг 4 — Разрешить Actions писать в репозиторий

1. Открой **Settings → Actions → General**
2. Прокрути вниз до **Workflow permissions**
3. Выбери **Read and write permissions**
4. Нажми **Save**

---

### Шаг 5 — Запустить сборку

1. Перейди в **Actions** (вкладка сверху)
2. Слева выбери **Build DEX**
3. Нажми **Run workflow → Run workflow**
4. Подожди 3-5 минут — увидишь зелёную галочку ✅
5. После сборки в папке `outputs/` появятся `deleted-messages.dex` и `actual.json`

---

### Шаг 6 — Настроить плагин

Открой файл `DeletedMessages.plugin` и замени в начале:

```python
GITHUB_USER = "ТУТ_ТВОЙ_НИКНЕЙМ"   # ← твой логин GitHub
GITHUB_REPO = "deleted-messages"
```

---

### Шаг 7 — Установить плагин в Exteragram

1. Скопируй `DeletedMessages.plugin` на телефон
2. В Exteragram: **Настройки → Плагины → Установить из файла**
3. Выбери файл
4. Плагин сам скачает DEX с GitHub при первом запуске

---

## Как обновить плагин

1. Измени код в `Main.kt`
2. Увеличь `VERSION_CODE` (например с 10 на 11)
3. Сделай `git push` или загрузи файл через браузер
4. GitHub Actions автоматически пересоберёт DEX
5. При следующем запуске Exteragram плагин покажет уведомление об обновлении

---

## Что умеет плагин

- 📋 Просмотр удалённых сообщений текущего чата через BottomSheet
- 👤 Отображение отправителя, времени удаления, типа медиа
- 🔔 Toast-уведомление при удалении сообщения (опционально)
- ✕ Удаление отдельных записей из кеша
- 🗑 Очистка всего кеша или только текущего чата
- ⚙️ Настройка лимита сообщений на чат
- 🔄 Автообновление DEX без переустановки плагина
