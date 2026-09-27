/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import java.text.Normalizer
import java.util.Locale

enum class SettingsCategory(val title: Int, val summary: Int) {
    CAPTURE(R.string.settings_category_capture, R.string.settings_summary_capture),
    RECORDING(R.string.settings_category_recording, R.string.settings_summary_recording),
    MONITORING(R.string.settings_category_monitoring, R.string.settings_summary_monitoring),
    AUDIO(R.string.settings_category_audio, R.string.settings_summary_audio),
    CONTROLS(R.string.settings_category_controls, R.string.settings_summary_controls),
    DISPLAYS(R.string.fold_settings_title, R.string.settings_summary_displays),
    TRANSFERS(R.string.webdav_queue_title, R.string.settings_summary_transfers),
    DIAGNOSTICS(R.string.settings_category_diagnostics, R.string.settings_summary_diagnostics),
}

data class SettingEntry(val id: String, val category: SettingsCategory, val title: Int, val keywords: String)

object SettingsCatalog {
    val entries = listOf(
        SettingEntry("proxy", SettingsCategory.CAPTURE, R.string.proxy_settings_title, "proxy proxies copia copy derivados derivatives edición editing offline resolución resolution bitrate Mbps tamaño size escala scaling original audio manual batería battery carga charging espacio space reserva reserve"),
        SettingEntry("geotagging", SettingsCategory.CAPTURE, R.string.geotagging_title, "geolocation geotagging GPS location ubicación localización coordenadas coordinates permiso permission aproximada approximate precisa precise privacidad privacy metadatos metadata"),
        SettingEntry("playback", SettingsCategory.CAPTURE, R.string.playback_settings_title, "reproduccion playback visor viewer archivos files media silenciar mute sonido bucle loop posicion frame fotograma"),
        SettingEntry("capture-naming", SettingsCategory.CAPTURE, R.string.capture_naming_title, "archivos files proyecto project nombres naming filename plantilla template prefijo prefix claqueta slate toma take UTC fecha date hora time UUID"),
        SettingEntry("media-sharing", SettingsCategory.CAPTURE, R.string.media_sharing_title, "compartir share exportar export originales originals metadatos metadata claqueta slate technical tecnico LUT cube privacidad privacy"),
        SettingEntry("media-gallery", SettingsCategory.CAPTURE, R.string.gallery_settings_title, "galeria gallery biblioteca catalogo catalog media archivos files buscar search filtro filter foto photo video audio claqueta slate toma take buena good fecha date orden sort"),
        SettingEntry("production-slate", SettingsCategory.CAPTURE, R.string.production_slate_title, "claqueta slate proyecto project cámara camera escena scene rollo reel lente lens toma take interior exterior dia day noche night buena good incremento increment metadatos metadata"),
        SettingEntry("lut-library", SettingsCategory.MONITORING, R.string.lut_library_title, "LUT cube biblioteca library importar exportar operador color look técnica technical hash dominio domain"),
        SettingEntry("monitoring-scopes", SettingsCategory.MONITORING, R.string.monitoring_title, "waveform vectorscopio vectorscope falso color false code zebra peaking opacidad opacity umbral threshold area segura safe guia aspect frecuencia Hz"),
        SettingEntry("webdav-queue", SettingsCategory.TRANSFERS, R.string.webdav_queue_title, "webdav transferencia transfer upload subida cola queue wifi celular cellular destino endpoint servidor server"),
        SettingEntry("project-timing", SettingsCategory.RECORDING, R.string.project_timing_title, "pausa pause reanudar resume sincronizacion synchronization off speed velocidad proyecto project rational racional captura capture fps silencio silent audio"),
        SettingEntry("timelapse", SettingsCategory.RECORDING, R.string.timelapse_settings_title, "pausa pause reanudar resume timelapse time lapse intervalo interval fotogramas frames project proyecto FPS cadence cadencia duracion duration limite limit"),
        SettingEntry("operator-controls", SettingsCategory.CONTROLS, R.string.operator_title, "botones buttons programables programmable volumen volume teclas keys inicio startup restaurar restore bloqueo lock toma recording ergonomia"),
        SettingEntry("presets", SettingsCategory.CONTROLS, R.string.presets_title, "preset preajuste guardar save actualizar update importar import exportar export C1 C2 configuracion configuration"),
        SettingEntry("image-processing", SettingsCategory.CAPTURE, R.string.image_processing_title, "estabilizacion stabilization OIS EIS optica optical electronica electronic ISP nitidez sharpness edge ruido imagen noise reduction procesamiento imagen processing"),
        SettingEntry("professional-exposure", SettingsCategory.CAPTURE, R.string.pro_exposure_title, "ISO shutter obturacion exposicion exposure angle angulo prioridad priority antibanding antiparpadeo 50 60 Hz Kelvin temperatura temperature tint tinte balance blancos white balance lock recording bloqueo toma"),
        SettingEntry("fold-displays", SettingsCategory.DISPLAYS, R.string.fold_settings_title, "plegable foldable pantalla exterior outer rear screen teleprompter brillo brightness autograbacion self recording temporizador timer countdown cuenta regresiva minimal espejo mirror vista previa preview color assist asistencia bisagra hinge mesa libro"),
        SettingEntry("layout", SettingsCategory.CONTROLS, R.string.mode_selector_style, "dial buttons botones selector interfaz"),
        SettingEntry("audio", SettingsCategory.AUDIO, R.string.audio_recording, "mic microphone microfono permiso permission"),
        SettingEntry("audio-permission", SettingsCategory.AUDIO, R.string.grant_microphone, "mic microphone microfono permiso permission"),
        SettingEntry("audio-format", SettingsCategory.AUDIO, R.string.professional_audio, "AAC WAV FLAC PCM formato format entrada input source ganancia gain digital manual decibeles dB medidor meter VU PPM RMS peak pico balística referencia hold escucha listening auriculares headphones bluetooth altavoz speaker AGC NS AEC frecuencia sample rate profundidad depth canales channels dispositivo device bitrate"),
        SettingEntry("burst", SettingsCategory.RECORDING, R.string.burst_count, "rafaga burst photo fotografia"),
        SettingEntry("bitrate", SettingsCategory.RECORDING, R.string.video_bitrate, "video calidad quality Mbps"),
        SettingEntry("geometry", SettingsCategory.RECORDING, R.string.recording_geometry, "geometria orientacion rotation raster"),
        SettingEntry("anamorphic", SettingsCategory.RECORDING, R.string.anamorphic, "desqueeze lente squeeze"),
        SettingEntry("accumulation", SettingsCategory.CAPTURE, R.string.accumulation_title, "light water stars bulb acumulacion accumulation exposicion larga pintura luz agua estrellas duracion intervalo memoria cancelar"),
        SettingEntry("bracket", SettingsCategory.CAPTURE, R.string.bracket_settings_title, "bracket horquillado cantidad count paso step ev exposiciones exposure separado hdr cancelacion progreso"),
        SettingEntry("photo-aspect", SettingsCategory.CAPTURE, R.string.photo_aspect_title, "aspecto aspect ratio recorte crop personalizado custom proporcion ancho alto width height JPEG RAW"),
        SettingEntry("photo-format", SettingsCategory.CAPTURE, R.string.photo_format_title, "foto photo fotografia formato format raw dng jpeg heic calidad quality pareja pair"),
        SettingEntry("photo-flash", SettingsCategory.CAPTURE, R.string.photo_flash_title, "flash fotografia foto photo automatico automatic forced forzado pulso pulse intensidad strength precaptura"),
        SettingEntry("torch", SettingsCategory.CAPTURE, R.string.flash_torch, "flash torch antorcha linterna luz light intensidad intensity strength LOG"),
        SettingEntry("zebra", SettingsCategory.MONITORING, R.string.monitor_zebra, "zebra cebra exposicion exposure"),
        SettingEntry("peaking", SettingsCategory.MONITORING, R.string.monitor_peaking, "peaking focus enfoque"),
        SettingEntry("histogram", SettingsCategory.MONITORING, R.string.histogram_default, "histogram histograma RGB luma"),
        SettingEntry("grid", SettingsCategory.MONITORING, R.string.composition_grid, "grid grilla composicion guia"),
        SettingEntry("grid-mode", SettingsCategory.MONITORING, R.string.composition_grid_mode, "grid thirds tercios aurea diagonal golden"),
        SettingEntry("horizon", SettingsCategory.MONITORING, R.string.horizon_level, "horizonte nivel level horizon"),
        SettingEntry("metering", SettingsCategory.CAPTURE, R.string.tap_exposure_metering, "tap touch toque exposicion exposure metering medicion"),
        SettingEntry("assist", SettingsCategory.MONITORING, R.string.log_view_assist, "LUT LOG Rec709 asistencia assist color"),
        SettingEntry("focus-lock", SettingsCategory.CAPTURE, R.string.af_lock_behavior, "focus enfoque AF bloqueo lock"),
        SettingEntry("zoom-lens", SettingsCategory.CAPTURE, R.string.zoom_lens_switch_mode, "zoom lente lens cambio switch automatico automatic manual preajuste preset tele gran angular wide pellizco pinch"),
        SettingEntry("timecode", SettingsCategory.RECORDING, R.string.settings_timecode, "timecode SMPTE tiempo FPS drop frame DF NDF"),
        SettingEntry("hardware", SettingsCategory.DIAGNOSTICS, R.string.hardware_truth, "hardware capacidades capabilities camara camera LOG RAW"),
        SettingEntry("modes", SettingsCategory.DIAGNOSTICS, R.string.settings_mode_availability, "modo mode supported disponible experimental"),
        SettingEntry("about", SettingsCategory.DIAGNOSTICS, R.string.about_title, "about acerca licencia license version ayuda help"),
    )

    fun search(query: String, category: SettingsCategory?, label: (Int) -> String): Set<String> {
        val words = normalize(query).split(Regex("\\s+")).filter(String::isNotEmpty)
        return entries.filter { entry ->
            (category == null || entry.category == category) && words.all { word ->
                word in normalize("${label(entry.title)} ${label(entry.category.title)} ${entry.keywords}")
            }
        }.mapTo(linkedSetOf()) { it.id }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}

/** Only these preferences change a running recording; structural settings, including manual
 * audio gain and requested AGC, remain pending until the next take. */
fun CameraSettings.withLivePreferencesFrom(next: CameraSettings): CameraSettings = copy(
    geotaggingEnabled = next.geotaggingEnabled,
    audioListening = next.audioListening,
    audioMeter = next.audioMeter,
    gallery = next.gallery,
    mediaSharing = next.mediaSharing,
    captureNaming = next.captureNaming,
    playback = next.playback,
    proxy = next.proxy,
    audioListeningOutputDeviceId = next.audioListeningOutputDeviceId,
    monitoring = next.monitoring,
    operation = next.operation,
    exposure = if (next.operation.lockDuringTake) exposure else next.exposure,
    whiteBalance = if (next.operation.lockDuringTake || recordingWhiteBalance == com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy.LOCK_ON_RECORD) whiteBalance else next.whiteBalance,
    subjectDisplay = next.subjectDisplay.copy(continueRecordingOnFold = subjectDisplay.continueRecordingOnFold),
    flashEnabled = if (next.operation.lockDuringTake) flashEnabled else next.flashEnabled,
    torchStrengthLevel = if (next.operation.lockDuringTake) torchStrengthLevel else next.torchStrengthLevel,
    zebraEnabled = next.zebraEnabled,
    peakingEnabled = next.peakingEnabled,
    histogramEnabled = next.histogramEnabled,
    histogramMode = next.histogramMode,
    compositionGridEnabled = next.compositionGridEnabled,
    compositionGridMode = next.compositionGridMode,
    horizonLevelEnabled = next.horizonLevelEnabled,
    logViewAssistEnabled = next.logViewAssistEnabled,
    modeSelectorStyle = next.modeSelectorStyle,
)
