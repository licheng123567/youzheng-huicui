import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles/ds-admin.css'   // 高保真设计系统（与原型同源）
import './styles/el-bridge.css'  // EL 主题桥接到 ds-admin tokens（须在 EL 默认样式后引入以覆盖）
import './styles/mobile.css'     // 移动作业端样式（.m-app 命名空间）
import router from './router'
import App from './App.vue'

createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
