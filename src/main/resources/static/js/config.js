/* ============================================================
 * Motcs 前端配置（部署时按需修改）
 * ============================================================
 * 系统类型下拉框选项来自本文件（window.MOTCS_CONFIG.systems），
 * 修改后刷新页面即生效，无需重新打包。
 * 租户下拉框选项来自后端租户配置表（GET /documents/v1/tenants），
 * 在此仅保留兜底默认值。
 */
window.MOTCS_CONFIG = {
    // 系统类型选项（枚举：congress=人大 / cppcc=政协 / party=党建 /
    // msw=社会工作部 / digital=数智统战 / other=综合平台）：
    // code 为实际传给后端的值，label 为下拉框显示文案
    systems: [
        {code: 'other', label: '综合平台'},
        {code: 'congress', label: '人大'},
        {code: 'cppcc', label: '政协'},
        {code: 'party', label: '党建'},
        {code: 'msw', label: '社会工作部'},
        {code: 'digital', label: '数智统战'}
    ]
};
