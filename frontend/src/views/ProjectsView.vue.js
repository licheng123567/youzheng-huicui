import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
const items = ref([]);
const scope = ref('');
const loading = ref(true);
onMounted(async () => {
    try {
        const res = await fetch('/v1/projects-scope-demo', {
            headers: { Authorization: `Bearer ${localStorage.getItem('token')}` },
        });
        if (!res.ok)
            throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        items.value = data.items;
        scope.value = data.scopeApplied;
    }
    catch (e) {
        ElMessage.error('加载项目失败：' + e.message);
    }
    finally {
        loading.value = false;
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.ElCard;
/** @type {[typeof __VLS_components.ElCard, typeof __VLS_components.elCard, typeof __VLS_components.ElCard, typeof __VLS_components.elCard, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    header: "项目列表 · 数据范围隔离演示",
}));
const __VLS_2 = __VLS_1({
    header: "项目列表 · 数据范围隔离演示",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
const __VLS_5 = {}.ElAlert;
/** @type {[typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ]} */ ;
// @ts-ignore
const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
    type: "info",
    closable: (false),
    ...{ style: {} },
    title: (`服务端按当前主体裁剪：${__VLS_ctx.scope}。换 cuihu_pl 登录会看不到阳光物业的项目。`),
}));
const __VLS_7 = __VLS_6({
    type: "info",
    closable: (false),
    ...{ style: {} },
    title: (`服务端按当前主体裁剪：${__VLS_ctx.scope}。换 cuihu_pl 登录会看不到阳光物业的项目。`),
}, ...__VLS_functionalComponentArgsRest(__VLS_6));
const __VLS_9 = {}.ElTable;
/** @type {[typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ]} */ ;
// @ts-ignore
const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
    data: (__VLS_ctx.items),
    border: true,
}));
const __VLS_11 = __VLS_10({
    data: (__VLS_ctx.items),
    border: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_10));
__VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.loading) }, null, null);
__VLS_12.slots.default;
const __VLS_13 = {}.ElTableColumn;
/** @type {[typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ]} */ ;
// @ts-ignore
const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
    prop: "id",
    label: "ID",
    width: "80",
}));
const __VLS_15 = __VLS_14({
    prop: "id",
    label: "ID",
    width: "80",
}, ...__VLS_functionalComponentArgsRest(__VLS_14));
const __VLS_17 = {}.ElTableColumn;
/** @type {[typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ]} */ ;
// @ts-ignore
const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
    prop: "name",
    label: "项目",
}));
const __VLS_19 = __VLS_18({
    prop: "name",
    label: "项目",
}, ...__VLS_functionalComponentArgsRest(__VLS_18));
const __VLS_21 = {}.ElTableColumn;
/** @type {[typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ]} */ ;
// @ts-ignore
const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
    prop: "orgName",
    label: "物业",
}));
const __VLS_23 = __VLS_22({
    prop: "orgName",
    label: "物业",
}, ...__VLS_functionalComponentArgsRest(__VLS_22));
const __VLS_25 = {}.ElTableColumn;
/** @type {[typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ]} */ ;
// @ts-ignore
const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
    prop: "status",
    label: "状态",
    width: "100",
}));
const __VLS_27 = __VLS_26({
    prop: "status",
    label: "状态",
    width: "100",
}, ...__VLS_functionalComponentArgsRest(__VLS_26));
var __VLS_12;
var __VLS_3;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            items: items,
            scope: scope,
            loading: loading,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
