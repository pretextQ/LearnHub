/**
 * mdTitle —— 「标题只存笔记标题字段，正文不再写 `# 标题`」这条约定的前端兜底。
 *
 * 背景：一级标题（`# xxx`）以前既出现在正文首行、又存在笔记标题字段里，打开笔记就看到两个标题；
 * 导出 .md 时还会在前面再拼一次，变成三处。
 * 后端保存时已经会剥掉重复的那一行（NoteService#stripDuplicatedTitle），
 * 这里再兜一层：导出前若正文首行正好是同一个标题，先剥掉，保证导出件里标题只出现一次。
 */

/** 标点与行内格式可能承载不同含义，只有相同的标题才算重复。 */
export function sameDocTitle(a, b) {
  const text = String(a ?? '').trim()
  return text !== '' && text === String(b ?? '').trim()
}

/**
 * 剥掉正文开头那行「与本笔记标题重复」的一级标题。
 * 只处理第一行就是 `# xxx` 且与标题相同的情况，其余内容一字不动。
 *
 * @param {string} content 正文
 * @param {string} title 笔记标题
 * @returns {string} 去掉重复标题行后的正文
 */
export function stripLeadingDocTitle(content, title = '') {
  const s = String(content || '')
  const m = s.match(/^ {0,3}#[ \t]+([^\r\n]*)(?:\r\n|\r|\n|$)/)
  if (!m) return s
  const heading = m[1].replace(/[ \t]+#+[ \t]*$/, '').trim()
  if (/^#+$/.test(heading) || !sameDocTitle(heading, title)) return s
  return s.slice(m[0].length).replace(/^(?:[ \t]*(?:\r\n|\r|\n))+/, '')
}
