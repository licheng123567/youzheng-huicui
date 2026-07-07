// HTML→Word(.doc) 零依赖导出：把正文 HTML 包成 Word 兼容的 Office HTML，Blob 落盘 .doc。
// WPS/Word/浏览器均可打开可打印；正文样式请用「内联 style」(scoped CSS 不会带入 .doc)。
// 用于催收单 / 民事起诉状 / 律师函三处文书导出，避免各处重复拼装。
export function downloadDoc(filename: string, bodyHtml: string, title = ''): void {
  const html =
    '<html xmlns:o="urn:schemas-microsoft-com:office:office" ' +
    'xmlns:w="urn:schemas-microsoft-com:office:word" ' +
    'xmlns="http://www.w3.org/TR/REC-html40"><head><meta charset="utf-8">' +
    '<title>' + escapeHtml(title) + '</title>' +
    '<style>body{font-family:"SimSun","宋体",serif;font-size:14px;line-height:2;color:#000;padding:24px}</style>' +
    '</head><body>' + bodyHtml + '</body></html>'
  // ﻿ BOM 让 Word 按 UTF-8 正确识别中文
  const blob = new Blob(['﻿', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename.toLowerCase().endsWith('.doc') ? filename : filename + '.doc'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>]/g, (c) => (c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'))
}
