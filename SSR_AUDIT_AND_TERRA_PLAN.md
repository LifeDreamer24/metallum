# SSR: аудит и план реализации для GPT-5.6 Terra

Дата: 2026-09-20. Исходный HEAD: `cca925a3910fce7ce8b0ebcbbb9d18d776c6ea3c`; рабочее дерево перед аудитом чистое.
Это передача задачи, а не новый канонический контракт и не акт визуальной/производительной приёмки.

## Цель и вывод

Сделать отражения воды читаемыми, цветными, непрерывными под скользящим углом и стабильными при движении, с измеренной стоимостью на M1 Pro. Сохранить внешний вид материалов вне этой задачи. Расширение SSR на металл, мокрые блоки и стекло — отдельный следующий проект: текущий вызов трассировщика находится в ветке воды.

Текущий SSR имеет системные ограничения. Он выполняет до 48 неравномерных шагов в view space, а затем скрывает недостоверные попадания набором fade-факторов. Это создаёт условия для чередования отражения и фона, особенно на дальних и почти горизонтальных лучах. Увеличение насыщенности или числа шагов само по себе задачу не решит.

Рекомендуемая последовательность: доказать активный SSR и причины полос → исправить координаты и пересечения → ввести перспективно корректный экранный обход → согласовать отражение с оптикой воды → закрыть стабильность в движении → ускорять только прошедший проверку качества алгоритм. Hi-Z и отдельная история отражений — предметные следующие ступени, с проверкой полной стоимости дополнительных проходов.

## Границы доказательств этого аудита

- Прочитаны текущие Java/GLSL-generating sources, захват/биндинги, lifecycle, тесты, benchmark launcher, история оптимизаций и temporal ABI. Реализация и настройки не менялись.
- Просмотрена архивная пара ON/OFF от 8 сентября. В ON заметны дополнительные прерывистые горизонтальные участки отражения дома/берега; часть подводного рисунка остаётся в OFF. Это историческая статическая иллюстрация, не воспроизведение текущей сессии и не доказательство единственной причины.
- Численно проверены размеры шагов и пример ошибки near-plane clipping непосредственно по текущим формулам.
- Выполнен `./gradlew advancedDirectLightingShaderUnitTest --console=plain`: `BUILD SUCCESSFUL`, 18 tasks, включая сборку native/Metal dependencies. Это подтверждает текущие shader contracts, но не качество картинки; полный `check` в этом аудите не запускался.
- Новый Minecraft run, GPU trace, запись движения и Tier C A/B в рамках этого аудита не выполнялись. Текущая производительность — `UNKNOWN`; предполагаемая связь конкретных эвристик с наблюдаемыми полосами — `SPECULATIVE` до абляций.
- `config/metallum-reflections.properties` и `run/config/metallum-reflections.properties` содержат `water_reflection_mode=voxels`. Это факт файлов данного checkout, а не доказательство настройки другого лаунчера или уже запущенной JVM.

Архивные изображения:

- [SSR ON](run/lighting-reference/ssr-quality-after-house-20260908/20260908T114259Z-g8eb23d40aec9-dirty-ssr-quality-after-house-20260908-off.png).
- [OFF control](run/lighting-reference/ssr-quality-off-house-20260908/20260908T114755Z-g8eb23d40aec9-dirty-ssr-quality-off-house-20260908-off.png).

Суффикс `-off.png` у этих файлов сам по себе не устанавливает режим SSR; он относится к именованию запуска. Новые артефакты должны содержать явно подтверждённый reflection mode.

## Что сейчас реально реализовано

1. `LevelRendererMixin:115–122` копирует main scene непосредственно перед `TRANSLUCENT`.
2. `ScreenSpaceReflectionRenderer:52–85` делает полные копии color/depth. Формат цвета наследуется от source; в FP16-сцене это RGBA16F, глубина — D32.
3. `PlanarReflectionRenderer.bind:254–262` сначала ставит planar/fallback ресурсы, затем SSR переопределяет color slot 11 и depth slot 9.
4. `AdvancedDirectLightingShaderPatcher:1150–1357` выполняет raymarch прямо в fragment shader воды. Нет отдельного SSR result texture, depth pyramid или SSR history.
5. `:1398–1445` смешивает SSR с analytic environment, отдельно смешивает Fresnel и visibility, после чего перемножает результат.
6. `:4259` добавляет specular/environment к цвету воды; далее сохраняются общие fog/alpha/composite пути. Их точный вклад надо измерить отдельно.

## Находки

### A. Шаги пропускают геометрию, а binary search не возвращает пропущенные попадания

Источник: `AdvancedDirectLightingShaderPatcher:1216–1264`.

`stepCount = clamp(ceil(screenSpanPixels * 0.20), 24, 48)`. Начиная с длины луча около 240 px бюджет уже упирается в 48, хотя экран Retina имеет ширину 3024 px. При этом позиции выбираются по мировой дальности, а не по покрытию экранных пикселей:

`t = maxDistance * u * (0.15 + 0.85*u)`.

Для maxDistance=96 последний шаг равен 3.6646 блока при 48 шагах и 7.2583 при 24. Это длина вдоль луча, не обязательно приращение view-Z. Тонкие отражатели могут целиком оказаться между выборками; одинаковая фаза шагов соседних пикселей способна дать структурные полосы.

Условие запуска refinement дополнительно требует `depthDiff <= thickness`, где thickness ограничена 0.65. Корректный переход через поверхность с большим overshoot отвергается до binary search. Пример: предыдущая глубина луча 9, текущая 11, поверхность 10, thickness 0.65. Пересечение есть, но diff=1 отвергается; следующие точки уже позади поверхности и не восстанавливают crossing.

### B. Есть конкретная ошибка отсечения лучей, направленных к камере

Источник: `:1175–1207`.

Near-plane maxDistance считается от `viewPosition`, затем начало сдвигается на `originBias`, но длина повторно не обрезается. Для `viewPosition.z=-5`, `rayDirection.z=0.5`, near=0.05, bias=0.06 получается maxDistance=9.9 и endpoint.z=-0.02. При обычном `clip.w=-view.z` проверка `endClip.w <= 0.05` полностью отвергает луч, хотя большая часть отрезка допустима.

Это подтверждённый дефект формул. Насколько часто он проявляется в пользовательском ракурсе — пока неизвестно. Исправление: bias сначала, clipping от фактического origin затем, с согласованным epsilon и единым near-plane контрактом. Не оставлять независимые пороги 0.05/0.1 в разных частях обхода.

### C. Confidence может превращать рябь в чередование SSR и fallback

Источник: `:1161–1167`, `:1292–1336`.

- Направление строится по `mix(flatNormal, waveNormal, 0.35)`.
- Луч сразу отвергается при `surfaceDeparture <= 0.01`; далее действует fade 0.015–0.08.
- Итоговый confidence — произведение edge, distance, residual, travel, departure и depth continuity. Даже шесть значений 0.8 дают только 0.262.
- `depthContinuity` учитывает худшего из четырёх соседей; один сосед на небе может обнулить отражение тонкого силуэта.
- При низком confidence первое найденное пересечение приводит к `return 0`.

На скользящих углах нормаль волн меняет departure около порога. Убирать всякую проверку физически недопустимого направления нельзя, но её текущая визуальная роль требует отдельной абляции. Последовательное перемножение эвристик не заменяет оценку достоверности попадания.

### D. Фильтрация не соответствует roughness и не восстанавливает hit coverage

Источник: `:1339–1356`, `:582–712`.

Три цветовых выборки поперёк луча сглаживают найденный цвет. Они не заполняют пропуски трассировки и не учитывают глубину соседних цветовых samples. Радиус зависит от `u`, не от roughness/проекции отражённого пятна. Направление фильтра нормализовано в UV до поправки на texel size, что нужно проверить на разных aspect ratio.

Процедурные water waves здесь не имеют явного footprint-фильтра по экранным производным; у горизонта это кандидат на aliasing нормалей. Это отдельный возможный источник полос, даже при правильном пересечении.

### E. Цвет и энергия отражения смешиваются нелинейно

Источник: `:1403–1408`, `:1431–1445`, `:4259–4263`.

SSR radiance, Fresnel и environment visibility независимо lerp-ятся одним confidence и затем перемножаются. Поэтому confidence одновременно меняет геометрическую достоверность, силу отражения и освещённость. Получается нелинейное затухание; в закрытых/ночных местах оно особенно заслуживает проверки.

SSR использует художественный Fresnel `0.04 + 0.96*(1-NoV)^3` с ограничением 0.85, тогда как остальная material-модель использует свой F0/Schlick. Геометрический луч использует ослабленную wave normal, Fresnel — полную. Это не единая модель отражения.

Нельзя по исходникам объявлять gamma или FP16 основной причиной блеклости: формат копии наследуется от scene. Нужна проверка linear/HDR source → sampled radiance → reflection contribution → fog/alpha → presentation. Возможное повторное затуманивание уже затуманенной source radiance и ослабление specular через alpha пока являются гипотезами.

### F. Временной реконструкции SSR нет

В `ScreenSpaceReflectionRenderer` нет history/result targets, reprojection, history rejection или temporal resolve. MetalFX не следует считать заменой такой реконструкции, особенно при MetalFX OFF.

`FrameStateAbi` уже содержит current/previous view/projection, jitter, reset generations и exposure. Эти данные можно переиспользовать после проверки актуальности на момент SSR capture; наличие ABI не означает наличия корректных water receiver motion/normal buffers.

### G. `capture_only` не отделяет стоимость capture от tracing

`shouldCapture()` учитывает `metallum.ssr.capture_only`. После копии `activeThisFrame=true`; `bind()` ставит полноразмерную depth texture. Шейдер признаёт SSR активным по `textureSize(depth).x > 1`, без проверки requested mode.

Следовательно, OFF + capture_only способен реально включать tracing на подходящей воде. Перед любым измерением OFF/capture/full необходимо разделить `captureValid` и `tracingEnabled` и добавить сквозной тест. Текущий тест проверяет только запрос захвата.

### H. Есть пробел в освобождении SSR ресурсов

`ScreenSpaceReflectionRenderer.destroy()` существует, но production-вызовов при поиске по `src/main/java` не найдено. `MetalDevice.close()` вызывает `PlanarReflectionRenderer.close()`, который SSR не закрывает. Resize уничтожает предыдущий target, но статические ссылки/самплеры SSR явно не сбрасываются при закрытии устройства. Tracked target может освобождаться общим механизмом; это не заменяет reset статического владельца.

Подключить SSR teardown к проверенному device/world lifecycle; проверить повторную инициализацию и deferred disposal. Это исправление корректности, не доказанная FPS-оптимизация.

### I. Старый PASS и текущие тесты не закрывают качество

Запись `OptimizationHistory.md:4358` описывает старые 16 шагов, 300+300 frames и маршрут `reflection-water-view-a`, но заявляет подтверждённое качество/экономичность. Она не соответствует текущему 24–48-step shader и правилам Tier C; из предыдущего аудита известно, что старые water-view routes не обеспечивали видимую воду. Историю не переписывать; добавить датированную оговорку при реализации.

`testScreenSpaceReflectionQualityContract:301` в значительной степени фиксирует строки и старые константы. Он не проверяет raster coverage, полосы или движение. Lifecycle/config tests подключаются через `FrozenReflectionFieldControllerTests`; отсутствие отдельной Gradle task для SSR не означает, что они не запускаются.

### J. Стоимость полного SSR пока не выделена

В capture path две полноэкранные копии. При 3024×1964 RGBA16F+D32 это около 67.97 MiB хранения snapshot; логические чтения+записи двух копий — около 135.94 MiB/кадр, без учёта cache/compression. Это оценка объёма, не измерение bandwidth или GPU ms.

Capture выполняется до `MetalGpuTiming.begin(TRANSLUCENT)` в mixin. Один translucent timer поэтому не показывает полную цену эффекта. Отдельных SSR стадий сейчас нет. Capture также не проверяет фактическое наличие видимого water receiver.

## План выполнения

Работать небольшими независимо проверяемыми изменениями. Перед началом перечитать текущие `AGENTS.md`, `docs/BENCHMARKING.md`, `OptimizationHistory.md`; проверить drift от указанного HEAD. Сначала диагностический/опциональный candidate, замена production только после соответствующих gates. Не смешивать исправление качества с заявлением об ускорении.

### P0 — воспроизводимая сцена, честные ON/OFF и диагностика

**Сделать:**

1. Зафиксировать build/source/settings/fixture digests, фактический launcher, active mode/style/lighting, extent и capture formats. Добавить `SSR_ADMISSION` с frame generation, capture valid, trace enabled, slot bindings и причиной отказа. Не писать логи на каждый пиксель/кадр.
2. Исправить capture-only разделение из G и teardown из H. OFF не должен запускать tracing; capture-only копирует, но финальная картинка остаётся OFF. Проверить mode switch, resize, device recreation.
3. Использовать house fixture, сначала проверить видимую воду и отражаемые объекты. Маршруты `reflection-house-voxel-v1`, `...-grazing-v1`, `...-topdown-v1`, `...-low-red-v1`, `...-dawn-elevated-v1` — кандидаты; слово voxel в имени не разрешает включать VOXELS. Не передавать `--vertex-reflection-experiment` для SSR.
4. Добавить отдельные диагностические виды: water mask, flat/wave normal, raw hit color без финального tint, hit/miss reason, hit UV/distance, iteration count, каждый confidence factor, итоговый confidence, specular contribution, fallback-only. Выводить экранным GPU overlay или в отдельный debug target. Счётчики читать асинхронно/после измерения, без sync readback в frame loop.
5. Снять идентичные OFF / SSR / SSR-flat-normal / SSR-frozen-waves пары. Для причинных абляций по одному отключать departure fade, continuity и final composition; эти небезопасные диагностические варианты не выпускать как fix.
6. Добавить явно аттестуемый SSR mode в benchmark settings/launcher/report. Сейчас отдельного `--ssr`/`--water-reflection-mode` в launcher нет: не придумывать работающую команду. Новый параметр обязан сохранять/восстанавливать настройки, отражаться в metadata и реально подтверждаться admission. OFF/capture/full сравнивать отдельным diagnostic contract; production old-SSR/new-SSR — с одинаковым mode.
7. Снять неизменённый production baseline Tier C до продвижения оптимизаций; diagnostic overlay и capture-only не включать в production measurement.

**Gate:** в нужной воде виден ненулевой SSR contribution; известны режим, кадр и размеры bound snapshot. Артефакты имеют независимый OFF control. Для полос есть конкретный diagnostic channel/абляция, а не только предположение. Если проблема остаётся в OFF, соответствующую часть направить в water optics/normal/transmission, не объявлять её SSR-дефектом.

### P1 — единые координаты и корректный отрезок луча

**Сделать:**

- Исправить B: сначала origin bias, затем near/viewport clipping. Согласовать view space, Y orientation, reverse-Z, camera-relative origin и render extent. Проверить обе ориентации rayDirection.z.
- Вместо универсального per-fragment UV offset передавать точные transforms, которыми реально получен snapshot. Если input position живёт в stable view space, явно преобразовать его в capture view space. Чистый jitter допускает постоянную UV-поправку, но bobbing/rotation/FOV не обязаны сводиться к ней для всей длины луча.
- Переиспользовать FrameState только после проверки capture-frame correspondence. Не менять общий lighting projection для всех L3/L4/L6 ради SSR.
- Передавать геометрическую нормаль поверхности до wave perturbation. Сейчас flat normal всегда world-up: явно исключить/отдельно определить поведение water sides, водопадов и underwater/backfaces.
- Выделить SSR helper в небольшой генератор/модуль, если это позволяет менять алгоритм без дублирования строк в `fullMaterialEnvEval`. Не делать общий рефакторинг shader patcher.

**Проверки:** численные случаи near clipping из B, UV reprojection известных точек на нескольких глубинах, sky-depth sentinel, finite/NaN bounds, jitter ±offset, bobbing/FOV, portrait/wide aspect, DRS resize. Ошибка reprojection на детерминированных точках ≤0.5 render pixel — предлагаемый критерий корректности. Проверить emitted GLSL → SPIR-V → MSL и `AMBIENT_ONLY` без активных SSR reads/bindings.

**Gate:** frozen flat water и неподвижные объекты не дают смещения отражения из-за несовпадения координат при изменении камеры.

### P2 — корректное покрытие луча и попадания

**Сделать:**

1. Заменить world-distance quadratic stepping перспективно корректным screen-space DDA. Интерполировать homogeneous quantities (`Q/w`, `1/w`), а не линейную view-Z по UV. Определять пересечение через диапазон глубины сегмента луча в посещаемом pixel и допустимый surface thickness.
2. Сначала сделать diagnostic reference со stride=1 и достаточным бюджетом до конца clipped segment. Это oracle качества, не кандидат на production FPS. Проверять короткие, почти вертикальные, почти горизонтальные и вырожденные экранные лучи.
3. Refinement выполнять для действительно найденного bracket/interval; большой overshoot endpoint не должен запрещать сам поиск. Различать пересечение непрерывной поверхности и ложный crossing на depth discontinuity. Thickness не увеличивать произвольно для заполнения дыр.
4. В production обходе ввести отдельные termination reasons: valid miss, offscreen, sky, disocclusion/invalid geometry, iteration budget. Budget exhaustion не выдавать за достоверный miss. Ограничение работы и дальность фиксировать в контракте качества.
5. Проверять hit normal по доступной геометрии. Если normal восстанавливается из depth, использовать выбор соседей одной поверхности и отмечать низкую достоверность у силуэта. Не вводить обязательный full-scene G-buffer ради одного теста нормали.
6. Возвращать структурированный результат: hit UV, distance, valid/confidence, termination reason. Цветовое семплирование и композицию отделить от поиска пересечения.

**Проверки:** независимые синтетические depth scenes с известным ответом — плоскость, ступенька, тонкий столб, забор/листва с cutout, foreground/background discontinuity, луч к камере, screen edge. Сравнить oracle и candidate на сохранённых depth inputs. Новый тест должен воспроизводить overshoot false miss из A и действительно падать на старой реализации.

**Gate:** на flat-normal static воде исчезает периодическая потеря отражённой геометрии в пределах представимой depth-buffer сцены. Повышение blur/brightness не считается прохождением gate.

### P3 — confidence, roughness, волны и насыщенный естественный цвет

**Сделать:**

- Разделить физическую валидность луча, численную достоверность hit и художественный вес отражения. Сначала диагностически показать каждый фактор. Геометрически допустимые grazing hits не должны исчезать только из-за произведения нескольких схожих штрафов.
- Смешивать согласованные reflection contributions один раз. Концептуально: `F * (C * L_ssr + (1-C) * L_fallback)`; visibility fallback применяется к fallback, а не повторно гасит уже освещённый SSR hit. Отдельно согласовать transmission/body energy и downstream alpha blending, чтобы specular не учитывался дважды и не ослаблялся случайно.
- Использовать согласованную normal/Fresnel модель. Сохранять художественную настройку стиля можно, но явным параметром, не компенсацией ошибок confidence. Проверить top-down прозрачность, grazing reflection и отсутствие засвета в пещере.
- Проследить linear/HDR/exposure/fog на каждом участке. Для color check использовать отражение красного, зелёного и нейтрального блоков с одинаковым освещением; смотреть raw radiance и final contribution. Не добавлять saturation boost, пока не найден участок потери цвета.
- Roughness должна задавать footprint отражения: явный LOD цветовой пирамиды либо небольшой edge-aware gather с согласованной глубиной. Не размазывать соседний foreground через силуэт. RGB остаётся linear HDR; miss/confidence хранятся отдельно от RGB.
- Фильтровать высокочастотный wave normal по экранному footprint на grazing/distant воде; сохранять крупные волны. Снижение общей wave amplitude не выдавать за исправление aliasing. Производные получать в корректном uniform execution context.
- `NATURAL` сохраняет свою художественную политику, `REALISM` допускает более выраженную оптику; `VANILLA` не получает незапрошенный water redesign.

**Gate:** читаются форма и цвет отражателя; нет повторяющихся полос на неподвижной и анимированной воде, нет серой плёнки, берегового halo, чрезмерно зеркального top-down или ночного analytic-sky свечения. Сравнение при одинаковой экспозиции; SDR PNG не закрывает HDR-приёмку.

### P4 — стабильность движения; отдельный SSR resolve при необходимости

После P1–P3 обязательно выполнить motion matrix ниже. Если стабильность достигается без истории, зафиксировать это видеодоказательством и не добавлять temporal pipeline автоматически. Если остаются мерцание coverage/subpixel details или нужна разреженная трассировка, реализовать следующий путь; не включать случайный jitter без реконструкции.

**Предлагаемая архитектура:**

`opaque snapshot → water receiver metadata → raw SSR → spatial/temporal resolve → обычный water composite`.

- До trace нужен конкретный producer water receiver depth, geometric/shading normal, roughness и mask. Opaque depth содержит дно, а не водную поверхность! Запроектировать water-only metadata prepass с проверкой opaque occlusion и выбором ближайшей water surface. Все transformations/wave time должны совпадать с final water draw.
- Не рисовать SSR поверх уже готовой сцены вслепую: итоговый water shader семплирует resolved reflection и продолжает существующий translucent composite. На перекрывающихся слоях воды/стекла явно определить поддерживаемый порядок; неподдерживаемый слой должен получить корректный fallback.
- Начать с детерминированного trace и небольшого edge-aware spatial resolve. Хранить radiance и confidence/validity без превращения miss в чёрный цвет. После этого добавлять history ping-pong, moments/variance и history length.
- Reprojection валидируется по receiver depth/normal/material, camera/world generations, hit distance/position и изменению нормали волны. Одних opaque motion vectors недостаточно. Для отражённого движущегося объекта и parallax проверить hit-aware reprojection; без достоверных hit motion/history — reject или короткая история, а не шлейф.
- Применять neighborhood/variance clipping и адаптивный history weight. Обновлять history на реально отрендерованных кадрах, не на FI-generated presentations.
- Reset при camera cut, teleport, world/dimension change, resize/DRS, projection discontinuity, SSR toggle, style/material changes и смене resource generation. Учесть pre-exposure ratio, если история хранится в pre-exposed space.
- Сначала full-resolution correctness variant. Половина ширины и высоты trace — это четверть числа лучей; разрешать её только после depth/normal-aware upsample и проверки тонких отражателей. Не смешивать water и land в одном low-res texel. Metadata precision и coverage должны поддерживать корректный upsample.
- Blue-noise/VNDF ray jitter — отдельный необязательный шаг для rough reflections после готового denoiser; не средство спрятать structured banding в мерцающем шуме.

**Gate:** slow pan/strafe не дрожит; быстрый разворот/прыжок не оставляет следов дома/дерева; при disocclusion stale hit исчезает в первом корректно определённом кадре, не размазывается длинной историей. Потеря детали из-за сильного blur — провал качества. Prepass/history overhead включён в полную стоимость SSR.

### P5 — ускорение с сохранением качества

К этому моменту есть oracle из P2, прошедший visual gate candidate и baseline. Рассматривать изменения по одному:

1. **Hi-Z traversal.** Построить conservative depth hierarchy и перескакивать только доказанно пустые участки; descent/refinement до full-resolution hit. Для reverse-Z явно выбрать representation и min/max семантику: nearest raw depth — max, в positive linear view-depth — min. Один nearest mip не описывает произвольную thickness interval; доказать безопасный skip для всех направлений луча и clear-depth. Не усреднять depth как цвет. При сомнениях min/max intervals, с измерением памяти/стоимости.
2. **Water-only work.** Не трассировать пиксели других материалов; для compute — tile classification, список активных tiles и bounded dispatch. Выключать capture/pyramid, когда conservative visibility гарантирует отсутствие нужных receivers. Не вводить CPU readback mask ради экономии.
3. **Reuse existing capture.** Искать возможность переиспользовать scene snapshot лишь при совпадении кадра, formats, render extent и места до translucency. Читать текстуру, одновременно являющуюся render attachment, нельзя. Удалять копию только после доказательства отсутствия read/write hazard.
4. **Adaptive resolution/ray allocation.** Половинная trace resolution и variance-guided density допускаются, когда P4 сохраняет тонкие детали и движение. Сравнивать с текущим принятым quality reference, а не с заведомо хуже настроенным baseline.
5. **Pipeline cost.** Профилировать capture, hierarchy, metadata, trace, resolve, composite и whole-frame GPU. Проверять encoder boundaries и bandwidth на Apple Silicon. Перенос fragment → compute и AMD wave-оптимизаций сам по себе не гарантирует ускорения на M1 Pro.

**Предлагаемый бюджет, не измеренный результат:** финальный SSR whole-frame GPU p95 не хуже старого активного SSR более чем на 5% на подтверждённой water-heavy сцене; CPU p95 без статистически значимой регрессии. Цель — тот же или меньший frame time при лучшей картинке. Полную добавочную стоимость против честного OFF сообщить отдельно. Если ради качества бюджет превышен, назвать результат quality upgrade с регрессией и сохранить candidate opt-in; не объявлять задачу выполненной по производительности.

Бюджет не следует механически копировать с одной сцены на весь продукт. Нужны water-heavy, mixed и no-water routes. Выигрыш должен превышать измеренную повторяемость текущих запусков. Для memory представить таблицу `format × extent × mip count × history count`, peak allocation и resize/recreation стабильность.

**Gate:** минимум два независимых Tier C baseline и candidate run по 1800 warmup + 3000 measurement frames, production detail/validation/capture OFF, одинаковые hardware/settings/fixtures, без Serious/Critical thermal. Сравнить whole-frame GPU p50/p95/p99 и FPS/CPU tails. Tier B stage timings использовать только для attribution. Негативный/noisy сложный optimization candidate удалить, решение записать в OptimizationHistory.

### P6 — окончательная приёмка и передача

| Проверка | Обязательный результат |
| --- | --- |
| Flat water, дом/тонкий столб, grazing ~2°, 5°, 15° | Непрерывные отражения в пределах экранной информации, без регулярных дыр |
| Анимированные волны, камера стоит | Нет полосатого переключения hit/fallback, нет aliasing мелкой ряби |
| Slow pan/strafe, forward/back, быстрый yaw, jump/bobbing | Нет дрожания силуэтов, прилипания к экрану и history trails |
| Объект/камера пересекает screen edge | Плавный переход к fallback, без растяжки последнего texel |
| Foreground occluder, disocclusion, движущаяся entity | Нет старого отражения после потери соответствия |
| Top-down, shoreline, underwater, water sides | Сохранены прозрачность/цвет воды и определённое поведение неподдерживаемых граней |
| Dawn/day/night, cave, яркий цветной reflector, HDR/SDR | Нет ошибочной потери chroma, clipping и неуместного sky glow |
| MetalFX OFF/ON, DRS, resize, toggle, world reload | Правильные extents, matrices, resets, отсутствие stale ресурсов |
| AMBIENT_ONLY и SSR OFF | Нет активного SSR trace/resolve; fallback bindings корректны |
| Water-heavy/mixed/no-water Tier C | Полная стоимость, повторяемость, отсутствие скрытой регрессии |

Существующий `reflection-house-voxel-motion-v1` — полезный старт, но он не покрывает сам по себе все прыжки, camera cuts и режимы; добавить недостающие детерминированные сценарии. Заморозить время волн для причинных static A/B, включить анимацию для отдельного motion test. Порог восприятия качества не заменять одной среднекадровой метрикой.

Выдать контактный лист одинаковых ракурсов, raw/final contribution views, motion clips, таблицу exact-source тестов, perf/memory receipt links и список оставшихся ограничений. Отдельные статусы: `CODE/ABI/CODEGEN`, `STATIC_VISUAL`, `MOTION`, `TIER_C_PERFORMANCE`, `HUMAN_ACCEPTANCE`. Без просмотра движения не ставить общий PASS. Человеческую оценку «красиво» оставить пользователю на финальном конкретном результате; до неё выполнить все автоматизируемые работы.

## Файлы и границы изменений

| Файл/область | Изменения |
| --- | --- |
| `ScreenSpaceReflectionRenderer.java` | Capture-vs-trace admission, resource ownership, optional pyramids/history orchestration |
| `LevelRendererMixin.java` | Точный pass placement, capture timing, optional water metadata dependency |
| `AdvancedDirectLightingShaderPatcher.java` | Trace helper, coordinate contract, confidence, waves/filter/composition; синхронно specialization anchors |
| `PlanarReflectionRenderer.java`, `MetalDevice.java` | Binding order и lifecycle, безопасное выключение/recreation |
| `FrameStateAbi.java`, bridge, `MetallumNative.swift` | Только необходимые новые bindings/passes; ABI offsets/types синхронно |
| `src/main/metal/` | Если нужен compute: отдельные SSR kernels с явным resource contract |
| SSR/config/shader tests и `build.gradle` | Поведенческие intersect/reprojection тесты, actual codegen и исполнение test entry points |
| benchmark routes/settings/launcher/report | SSR mode/source admission, diagnostic split, coverage и reproducible A/B |

Соблюдать Render Thread confinement, pooled packet/ring allocations и отложенное освобождение через существующий GPU completion механизм. Не добавлять `Arena.allocate`, texture creation или GPU→CPU sync внутрь steady-state frame loop. Новый compute/metadata pass должен иметь явные dependencies и корректную работу при нескольких in-flight frames.

Сначала запускать целевые существующие задачи `advancedDirectLightingShaderUnitTest`, `frozenReflectionFieldUnitTest`, `metalRuntimeUnitTest` и новые поведенческие тесты; после изменения ABI — соответствующие Java/native ABI validation tasks; перед сдачей — `./gradlew check --console=plain`. Старые string tests обновлять на новый контракт, не оставлять обязательные «48 steps» как определение качества.

## Чего не делать

- Не считать повышение MAX_STEP_COUNT до 96/128 достаточным решением.
- Не увеличивать thickness, saturation, reflection strength или blur, чтобы скрыть ложные hits/полосы.
- Не включать случайный ray jitter до spatial/temporal reconstruction.
- Не требовать full deferred renderer, полноценный G-buffer, Metal 4 migration или voxel rewrite ради SSR.
- Не обещать отражение невидимой/закрытой камерой геометрии средствами одного depth buffer. Стабильный fallback обязателен; voxel/planar hybrid — отдельное решение с отдельной ценой и режимом.
- Не включать старый default-off temporal-L6 или voxel experiment как «бесплатную» основу SSR.
- Не называть снижение resolution/coverage/качества оптимизацией без сравнения изображения и движения.
- Не выдавать `gradlew check`, static PNG, capture-only delta или исторический PASS за визуальную/производительную приёмку.

## Технические ориентиры

- Перспективно корректный screen-space DDA и проблема пропущенных пикселей при 3D stepping: [McGuire & Mara, Efficient GPU Screen-Space Ray Tracing](https://jcgt.org/published/0003/04/04/paper.pdf). Использовать алгоритмическую основу, адаптируя depth/projection conventions Metallum.
- Иерархический обход, классификация tiles и отдельная реконструкция: [официальная документация FidelityFX SSSR](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/stochastic-screen-space-reflections/). Это архитектурный ориентир; скорость AMD implementation не переносится на M1 Pro.
- Проверки history/disocclusion и отражённого parallax: [официальная документация FidelityFX Denoiser](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/denoiser/). Не копировать его входные буферы, пока в Metallum нет подтверждённых эквивалентов.

## Короткое поручение Terra

Прочитай этот файл и текущие исходники. Выполняй P0→P6, исправляя установленные причины и сохраняя отдельный проверяемый результат каждого этапа. Начни с активного режима, честного capture-only, координат и quality oracle. Не пытайся сделать картинку красивой набором коэффициентов до исправления пересечений. Отдельную историю/Hi-Z добавляй по указанным условиям и измеряй всю цепочку. Завершение — качественная вода в движении плюс честный Tier C отчёт; при непрохождении gate сообщай конкретную причину и незавершённую часть, не ставь формальный общий PASS.
