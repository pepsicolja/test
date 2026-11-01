# Virtual Master 3.2.86: предварительный статический анализ

Получен комплект с https://virtual-master-android-clone.apk.gold/ через опубликованную ссылку загрузки apk.gold. Это стороннее зеркало; совпадение с APK Telegram независимо не установлено.

## Проверенные факты

- APK: `apks/Virtual_Master-3.2.86.apk`, пакет `com.clone.android.dual.space`, версия 3.2.86, minSdk 22, targetSdk 36.
- SHA-256 APK: `5af0067ecee81487632895c9947542740a64ba5a20cd719f817cdf275a068c62`.
- SHA-256 сертификата, извлечённого из APK: `23BDC5D92C5DCA13322FFDE2C624FDCC89F482F77BF3063E8A7DD7EF11DB73FD`. Извлечение сертификата не является независимой проверкой происхождения APK.
- В комплекте `rom_7_1_2.apk`: manifest.json описывает Android 7.1.2, rom_version 1059, support_a32=true, support_a64=true; два файла rom.img и rom1.img. Их формат и содержимое пока не расшифрованы; расширение .img не доказывает наличие ext4 или гостевого ядра.
- Нативные компоненты: libvm.so, libkr32.so/libkr64.so и варианты .11/.12, libkrloader32.so/libkrloader64.so.
- `file` определяет libkr32 как ARM32 ELF, libkr64 как AArch64 ELF; ELF-интерпретаторы указывают на собственные libkrloader в каталоге приложения.
- `NativeHelper.startOS(int,int,int,String)` — JNI-интерфейс запуска ОС; рядом есть получение списка гостевых процессов и отчётов.
- `BinderService` имеет нативные getBinderVersion/setupBinder, работает с Parcel и прокси Java. Это подтверждает специальную интеграцию Binder, но само по себе не доказывает полную эмуляцию драйвера.
- В DEX присутствуют интерфейсы nativeStartHALMgr, nativeAddSurface/nativeRemoveSurface, nativeOnTouchEvent, nativeCameraPreview, nativeSensorChanged, nativeGPSNmeaChanged. Они указывают на собственный слой интеграции экрана, ввода и оборудования.
- В составе APK не найдены файлы с именами QEMU или PRoot. В проверенных строках/символах libvm.so и libkr64.so не найдено характерных qemu/proot/cpu_exec/tcg_ обозначений. Обфускация не позволяет считать это доказательством отсутствия встроенного или загружаемого компонента.
- APK и нативные библиотеки сильно обфусцированы; jadx частично декомпилирует DEX. Исполнение кода APK не проводилось.

## Вывод и ограничения

Подтверждён собственный нативный runtime, загрузчики и мосты к системным службам/оборудованию. Гипотеза — адаптированный гостевой Android со специальным слоем совместимости; точный механизм системных вызовов, использование ядра хоста, механизм ARM32 на ARM64-only и наличие скрытого эмулятора пока не установлены. Нельзя обоснованно объявить движок QEMU, PRoot, полноценным гипервизором или точным аналогом контейнеров VineOS.

Для VineOS полезное направление — разделить запуск ОС, Binder, графику/ввод и совместимость ABI на отдельные backend-интерфейсы. Простая упаковка AOSP Android 5 в .vrom не воспроизводит эту архитектуру. Наличие ARM32 ELF не доказывает возможность их запуска на любом ARM64-only устройстве.

Ни запуск на POCO F6/Android 16, ни загрузка Android 5 не проверены. Сборка VineOS не изменялась.

Подтверждённые классы JNI: DisplayService.nativeAddSurface(..., android.view.Surface, ...), InputService.nativeOnTouchEvent(...), HALManager.nativeStartHALMgr/nativeCameraPreview/nativeSensorChanged/nativeGPSNmeaChanged. Сигнатуры сохранены в native-methods.json.
