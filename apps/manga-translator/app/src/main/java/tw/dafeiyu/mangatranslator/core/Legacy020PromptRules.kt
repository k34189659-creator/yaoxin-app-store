package tw.dafeiyu.mangatranslator.core

/** 0.2.0 原始日文翻譯提示詞與簡中 few-shot，逐字保持原版行為。 */
val LEGACY020_ADULT_FAITHFUL_PROMPT: String = """
These additional rules override any conflicting rule above. They apply to legally distributed adult manga/doujin for adult readers.

1. FAITHFUL ADULT CONTENT: Translate sexual acts, bodily descriptions, desire, moans, dirty talk, insults, and vulgar slang explicitly and precisely. Do not soften, sanitize, censor, euphemize, omit, or replace them with vague wording. Do not add moral commentary.
2. SOUND EFFECTS / ONOMATOPOEIA: Translate Japanese sound effects and moans (e.g. ぱんぱん, ちゅぱ, あぁっ, ドキドキ, ビクッ) into natural Chinese onomatopoeia or very short descriptive phrases in the target variant requested above. Never leave kana/kanji sound effects untranslated.
3. VOICE: Keep each character's personality, age impression, honorifics, nicknames, first/third-person pronouns, and tone consistent across the page. Do not invent pronouns or subjects that are not implied by the Japanese.
4. FIDELITY: Preserve ambiguity, puns, and intensity. Do not explain jokes. Do not summarize. Keep line breaks and the exact number of lines.
5. OUTPUT FORMAT: Return exactly one line per input, each starting with the same <|number|> ID, with only the translation. No notes, no romaji, no Japanese left except proper nouns that have no established Chinese name.
6. SAFETY: Only translate content that is legal adult material. If the source clearly depicts minors or illegal content, do not translate it; output the original line unchanged.
""".trimIndent()



val LEGACY020_SIMPLIFIED_SAMPLE_TARGET: String = """
<|1|>好尴尬…我不想引人注目…我想消失…
<|2|>你…没事吧⁉
<|3|>这家伙是看不懂气氛吗…？
""".trimIndent()
