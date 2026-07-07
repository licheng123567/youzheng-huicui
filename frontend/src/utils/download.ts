import { ElMessage } from 'element-plus'

// 鉴权二进制下载：录音音频 / 存证证书 等端点需带 Authorization 头，原生 <a href download> 不能带头，
// 故 fetch(带 Bearer) → blob → objectURL → 临时 <a download> 落盘。与 RecordingAudioPlayer 取流同源。
// 返回是否成功；404/失败弹提示（可传 notFoundMsg 定制“暂无文件”文案）。
export async function downloadAuthedFile(
  path: string,
  filename: string,
  notFoundMsg = '暂无文件',
): Promise<boolean> {
  try {
    const res = await fetch(path, {
      headers: { Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
    })
    if (res.status === 404) { ElMessage.warning(notFoundMsg); return false }
    if (res.status === 409) {
      // 备案证书未就绪等业务态：尽量取后端 message
      let msg = '文件未就绪'
      try { msg = (await res.json())?.message || msg } catch { /* 非 JSON */ }
      ElMessage.warning(msg); return false
    }
    if (!res.ok) { ElMessage.error('下载失败（' + res.status + '）'); return false }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    return true
  } catch (err: any) {
    ElMessage.error('下载失败：' + (err?.message ?? ''))
    return false
  }
}
