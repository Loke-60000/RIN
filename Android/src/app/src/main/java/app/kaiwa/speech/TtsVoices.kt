// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.speech

/**
 * Catalog of downloadable on-device voices — Piper VITS models hosted by the sherpa-onnx project
 * (MIT/permissive). Each tarball is self-contained (model + tokens + espeak-ng-data), so a voice is
 * usable offline the moment it's extracted. We list one good default voice per locale; the user
 * installs the ones they want, Gboard-style.
 */
data class TtsVoice(
  /** BCP-47-ish locale tag, e.g. "fr_FR" — also the on-disk directory name. */
  val locale: String,
  /** Human-readable language name shown in the picker. */
  val name: String,
  /** Release asset filename (the extracted top folder is this minus ".tar.bz2"). */
  val asset: String,
  /** Approximate download size in MB, for the UI. */
  val approxMb: Int,
) {
  val url: String
    get() = "$BASE_URL$asset"

  /** ISO-639 language part ("fr" from "fr_FR"), used to match the device language. */
  val language: String
    get() = locale.substringBefore('_')

  companion object {
    const val BASE_URL =
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
  }
}

/** The curated voice list — common languages first, then alphabetical by name. */
val TTS_VOICES: List<TtsVoice> =
  listOf(
    TtsVoice("en_US", "English (US)", "vits-piper-en_US-amy-medium.tar.bz2", 63),
    TtsVoice("en_GB", "English (UK)", "vits-piper-en_GB-alan-medium.tar.bz2", 63),
    TtsVoice("fr_FR", "French", "vits-piper-fr_FR-siwis-medium.tar.bz2", 63),
    TtsVoice("es_ES", "Spanish (Spain)", "vits-piper-es_ES-davefx-medium.tar.bz2", 63),
    TtsVoice("es_MX", "Spanish (Mexico)", "vits-piper-es_MX-ald-medium.tar.bz2", 63),
    TtsVoice("de_DE", "German", "vits-piper-de_DE-thorsten-medium.tar.bz2", 63),
    TtsVoice("it_IT", "Italian", "vits-piper-it_IT-paola-medium.tar.bz2", 63),
    TtsVoice("pt_BR", "Portuguese (Brazil)", "vits-piper-pt_BR-cadu-medium.tar.bz2", 63),
    TtsVoice("pt_PT", "Portuguese (Portugal)", "vits-piper-pt_PT-tugao-medium.tar.bz2", 63),
    TtsVoice("ru_RU", "Russian", "vits-piper-ru_RU-denis-medium.tar.bz2", 63),
    TtsVoice("zh_CN", "Chinese", "vits-piper-zh_CN-chaowen-medium.tar.bz2", 63),
    TtsVoice("nl_NL", "Dutch", "vits-piper-nl_NL-alex-medium.tar.bz2", 63),
    TtsVoice("pl_PL", "Polish", "vits-piper-pl_PL-darkman-medium.tar.bz2", 63),
    TtsVoice("tr_TR", "Turkish", "vits-piper-tr_TR-dfki-medium.tar.bz2", 63),
    TtsVoice("uk_UA", "Ukrainian", "vits-piper-uk_UA-ukrainian_tts-medium.tar.bz2", 63),
    TtsVoice("ar_JO", "Arabic", "vits-piper-ar_JO-kareem-medium.tar.bz2", 63),
    TtsVoice("hi_IN", "Hindi", "vits-piper-hi_IN-pratham-medium.tar.bz2", 63),
    TtsVoice("vi_VN", "Vietnamese", "vits-piper-vi_VN-vais1000-medium.tar.bz2", 63),
    TtsVoice("sv_SE", "Swedish", "vits-piper-sv_SE-alma-medium.tar.bz2", 63),
    TtsVoice("no_NO", "Norwegian", "vits-piper-no_NO-talesyntese-medium.tar.bz2", 63),
    TtsVoice("da_DK", "Danish", "vits-piper-da_DK-talesyntese-medium.tar.bz2", 63),
    TtsVoice("fi_FI", "Finnish", "vits-piper-fi_FI-harri-medium.tar.bz2", 63),
    TtsVoice("cs_CZ", "Czech", "vits-piper-cs_CZ-jirka-medium.tar.bz2", 63),
    TtsVoice("sk_SK", "Slovak", "vits-piper-sk_SK-lili-medium.tar.bz2", 63),
    TtsVoice("sl_SI", "Slovenian", "vits-piper-sl_SI-artur-medium.tar.bz2", 63),
    TtsVoice("hu_HU", "Hungarian", "vits-piper-hu_HU-anna-medium.tar.bz2", 63),
    TtsVoice("el_GR", "Greek", "vits-piper-el_GR-rapunzelina-low.tar.bz2", 30),
    TtsVoice("ca_ES", "Catalan", "vits-piper-ca_ES-upc_ona-medium.tar.bz2", 63),
    TtsVoice("eu_ES", "Basque", "vits-piper-eu_ES-antton-medium.tar.bz2", 63),
    TtsVoice("fa_IR", "Persian", "vits-piper-fa_IR-amir-medium.tar.bz2", 63),
    TtsVoice("id_ID", "Indonesian", "vits-piper-id_ID-news_tts-medium.tar.bz2", 63),
    TtsVoice("sr_RS", "Serbian", "vits-piper-sr_RS-serbski_institut-medium.tar.bz2", 63),
    TtsVoice("sq_AL", "Albanian", "vits-piper-sq_AL-edon-medium.tar.bz2", 63),
    TtsVoice("lv_LV", "Latvian", "vits-piper-lv_LV-aivars-medium.tar.bz2", 63),
    TtsVoice("lb_LU", "Luxembourgish", "vits-piper-lb_LU-marylux-medium.tar.bz2", 63),
    TtsVoice("is_IS", "Icelandic", "vits-piper-is_IS-bui-medium.tar.bz2", 63),
    TtsVoice("ka_GE", "Georgian", "vits-piper-ka_GE-natia-medium.tar.bz2", 63),
    TtsVoice("kk_KZ", "Kazakh", "vits-piper-kk_KZ-iseke-x_low.tar.bz2", 20),
    TtsVoice("ku_TR", "Kurdish", "vits-piper-ku_TR-berfin_renas-medium.tar.bz2", 63),
    TtsVoice("ml_IN", "Malayalam", "vits-piper-ml_IN-arjun-medium.tar.bz2", 63),
    TtsVoice("ne_NP", "Nepali", "vits-piper-ne_NP-chitwan-medium.tar.bz2", 63),
    TtsVoice("cy_GB", "Welsh", "vits-piper-cy_GB-bu_tts-medium.tar.bz2", 63),
    TtsVoice("sw_CD", "Swahili", "vits-piper-sw_CD-lanfrica-medium.tar.bz2", 63),
    TtsVoice("ur_PK", "Urdu", "vits-piper-ur_PK-fasih-medium.tar.bz2", 63),
  )

/** The catalog entry for [locale], if listed. */
fun ttsVoiceFor(locale: String): TtsVoice? = TTS_VOICES.firstOrNull { it.locale == locale }
