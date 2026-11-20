package tw.dafeiyu.mangatranslator.core

/**
 * 本 App 的翻譯規則，接在引擎內建 prompt 之後（override 一般規則）。
 * 目的：成人向漫畫的語境要「忠實」而不是「安全化」——不柔化、不刪減、擬聲詞要翻、人物語氣一致。
 * 僅適用合法成人作品；不要求模型繞過任何 provider 的安全政策。
 */
val ADULT_FAITHFUL_PROMPT: String = """
These additional rules override any conflicting rule above. They apply to legally distributed adult manga/doujin for adult readers.

1. FAITHFUL ADULT CONTENT: Translate sexual acts, bodily descriptions, desire, moans, dirty talk, insults, and vulgar slang explicitly and precisely. Do not soften, sanitize, censor, euphemize, omit, or replace them with vague wording. Do not add moral commentary.
2. SOUND EFFECTS / ONOMATOPOEIA: Translate clear dialogue and vocalizations when they are part of the OCR dialogue input. Do not invent translations for graphic sound effects, stylized lettering, decorations, or text already intentionally preserved by visual analysis. The rendering stage must not erase uncertain graphic effects.
3. VOICE: Keep each character's personality, age impression, honorifics, nicknames, first/third-person pronouns, and tone consistent across the page. Do not invent pronouns or subjects that are not implied by the source language.
4. FIDELITY: Preserve ambiguity, puns, and intensity. Do not explain jokes. Do not summarize. Keep line breaks and the exact number of lines.
5. OUTPUT FORMAT: Return exactly one line per input, each starting with the same <|number|> ID, with only the translation. No notes, no romanization. Keep proper nouns without established Chinese names when appropriate.
6. SAFETY: Only translate content that is legal adult material. If the source clearly depicts minors or illegal content, do not translate it; output the original line unchanged.
""".trimIndent()


/** 簡體中文舊目標相容：三行須與日文／英文來源 few-shot 一一對應。 */
val SIMPLIFIED_SAMPLE_TARGET: String = """<|1|>你还好吗？
<|2|>今天天气真好。
<|3|>你在做什么？""".trimIndent()

/** 0.4.0：按來源語言選 few-shot，避免英文 OCR 卻被日文範例引導。 */
val JAPANESE_SAMPLE_SOURCE = """<|1|>大丈夫？
<|2|>今日はいい天気ね。
<|3|>何をしているの？"""
val JAPANESE_SAMPLE_TARGET = """<|1|>你還好嗎？
<|2|>今天天氣真好呢。
<|3|>你在做什麼？"""
val ENGLISH_SAMPLE_SOURCE = """<|1|>Are you okay?
<|2|>It is a lovely day.
<|3|>What are you doing?"""
val ENGLISH_SAMPLE_TARGET = """<|1|>你還好嗎？
<|2|>今天天氣真好。
<|3|>你在做什麼？"""
