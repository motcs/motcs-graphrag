/* ============================================================
 * Motcs 知识图谱前端 - 主逻辑
 * ============================================================ */

const API_BASE = '/api/documents';

/* ---------- 全局状态 ---------- */
const state = {
    currentTab: 'search',
    selectedFiles: [],
    graphNetwork: null,
    history: [],
    sessionId: null,
    currentTitle: '',
    currentSources: [],
    historyBatchMode: false,
    historySelected: new Set(),
    docsRefreshTimer: null,
    abortController: null,
    currentQuestion: '',
    currentAnswer: '',
    currentAiBubble: null,
    msgOffset: 0,
    msgHasMore: true,
    msgLoading: false,
    draftMap: {},  // sessionId -> 未发送的草稿
    docSearch: '',
    docStatusFilter: 'all',
};

/* ---------- 配置持久化 ---------- */
const CFG_KEY = 'motcs_cfg';
function saveCfg() {
    localStorage.setItem(CFG_KEY, JSON.stringify({
        user: $('globalUser').value, tenant: $('globalTenant').value, system: $('globalSystem').value
    }));
}
function loadCfg() {
    try {
        const c = JSON.parse(localStorage.getItem(CFG_KEY) || '{}');
        if (c.user) $('globalUser').value = c.user;
        if (c.tenant) $('globalTenant').value = c.tenant;
        if (c.system) $('globalSystem').value = c.system;
    } catch {}
}

/* ---------- 工具函数 ---------- */
function $(id) { return document.getElementById(id); }

function genSessionId() {
    return 'sess_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
}

function formatSize(bytes) {
    if (!bytes) return '-';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(2) + ' MB';
}

function formatTime(dt) {
    if (!dt) return '-';
    try {
        const d = new Date(dt);
        return d.toLocaleString('zh-CN', { hour12: false });
    } catch { return dt; }
}

function showToast(msg, type = 'info') {
    const toast = $('toast');
    const inner = $('toastInner');
    inner.className = `px-5 py-3 rounded-xl shadow-2xl text-sm flex items-center gap-2 toast-${type}`;
    const icons = {
        success: '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>',
        error: '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>',
        info: '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>'
    };
    inner.innerHTML = (icons[type] || icons.info) + `<span>${msg}</span>`;
    toast.classList.remove('hidden');
    clearTimeout(showToast._t);
    showToast._t = setTimeout(() => toast.classList.add('hidden'), 3500);
}

function getTenant() { return $('globalTenant').value.trim() || 'default'; }
function getSystem() { return $('globalSystem').value.trim() || 'default'; }
function getUser() { return $('globalUser').value.trim() || 'anonymous'; }

/**
 * Markdown 渲染（带 marked 库检测和降级）
 */
function initMarkdown() {
    if (typeof marked !== 'undefined' && marked.setOptions) {
        marked.setOptions({ gfm: true, breaks: true });
    }
}

function renderMarkdown(text) {
    if (!text) return '';
    // 去除首尾空白
    let t = text.replace(/^\s+/, '').replace(/\s+$/, '');
    // 修复 AI 常见不规范写法：###标题 → ### 标题
    t = t.replace(/^(#{1,6})([^ #\t\n])/gm, '$1 $2');
    // 修复：**加粗** 中间无空格问题（一般不需要，这里兜底）
    if (typeof marked !== 'undefined' && typeof marked.parse === 'function') {
        try {
            return marked.parse(t);
        } catch (e) {
            console.warn('marked 渲染失败:', e);
        }
    }
    // 降级：转义 + 换行
    return escapeHtml(t).replace(/\n/g, '<br>');
}

function syncLabels() {
    const el = $('currentSessionId');
    if (state.sessionId) {
        el.textContent = state.currentTitle || (state.sessionId.slice(0, 16) + '...');
        el.title = state.sessionId;
    } else {
        el.textContent = '未开始';
        el.title = '';
    }
}

/* ---------- Tab 切换 ---------- */
function switchTab(tab) {
    state.currentTab = tab;
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tab);
    });
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.add('hidden'));
    $(`tab-${tab}`).classList.remove('hidden');

    if (tab === 'documents') loadDocuments();
    if (tab === 'graph' && !state.graphNetwork) {
        // 首次进入图谱页不自动加载，等用户点击
    }
}

document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});

/* ---------- 健康检查 ---------- */
async function checkHealth() {
    try {
        const res = await fetch(`${API_BASE}/health`);
        if (res.ok) {
            $('healthDot').className = 'w-2 h-2 rounded-full bg-green-400';
            $('healthText').textContent = '服务正常';
        } else {
            $('healthDot').className = 'w-2 h-2 rounded-full bg-yellow-400';
            $('healthText').textContent = '异常';
        }
    } catch {
        $('healthDot').className = 'w-2 h-2 rounded-full bg-red-400';
        $('healthText').textContent = '离线';
    }
}

/* ---------- 聊天辅助函数 ---------- */
function scrollToBottom() {
    const el = $('chatMessages');
    el.scrollTop = el.scrollHeight;
}

function clearChat() {
    $('chatMessages').innerHTML = `
        <div id="emptyState" class="h-full flex flex-col items-center justify-center text-center">
            <div class="w-16 h-16 rounded-2xl bg-gradient-to-br from-primary-500 to-purple-600 flex items-center justify-center mb-4">
                <svg class="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"/></svg>
            </div>
            <h2 class="text-xl font-bold text-gray-200 mb-2">有什么可以帮你的？</h2>
            <p class="text-sm text-gray-500">基于向量检索 + Neo4j 多跳图谱的 GraphRAG 智能问答</p>
        </div>`;
}

function appendUserMessage(text) {
    const empty = $('emptyState');
    if (empty) empty.remove();
    const row = document.createElement('div');
    row.className = 'msg-row user';
    row.innerHTML = `
        <div class="msg-avatar">U</div>
        <div class="msg-bubble">${escapeHtml(text)}</div>`;
    $('chatMessages').appendChild(row);
    scrollToBottom();
}

function appendAIMessage() {
    const row = document.createElement('div');
    row.className = 'msg-row ai';
    row.innerHTML = `
        <div class="msg-avatar">AI</div>
        <div class="msg-bubble">
            <div class="ai-content typing-cursor"><span class="text-gray-500">思考中...</span></div>
            <div class="msg-sources hidden"></div>
            <div class="msg-actions hidden">
                <button class="msg-copy-btn" title="复制回答">复制</button>
                <button class="msg-stop-btn" title="停止生成">停止</button>
            </div>
            <div class="msg-status hidden"></div>
        </div>`;
    $('chatMessages').appendChild(row);
    scrollToBottom();
    const bubble = row.querySelector('.msg-bubble');
    bubble.querySelector('.msg-copy-btn').addEventListener('click', () => {
        const text = row.querySelector('.ai-content').innerText;
        navigator.clipboard.writeText(text).then(() => showToast('已复制到剪贴板', 'success'));
    });
    return row.querySelector('.ai-content');
}

function renderSourcesInMessage(sourcesEl, sources) {
    if (!sources || sources.length === 0) { sourcesEl.classList.add('hidden'); return; }
    sourcesEl.classList.remove('hidden');
    sourcesEl.innerHTML = `<div class="sources-header">本轮引用 · ${sources.length} 篇</div><div class="sources-scroll">` +
        sources.map((s, i) => {
            const name = s.title || s.fileName || '未命名文档';
            const chunk = s.chunkIndex != null ? `第 ${s.chunkIndex} 段` : '';
            const page = s.pageNumber != null && s.pageNumber !== '' ? `第 ${s.pageNumber} 页` : '';
            const meta = [page, chunk].filter(Boolean).join(' · ');
            const hierarchy = s.titleHierarchy ? `<div class="source-hierarchy">${escapeHtml(s.titleHierarchy)}</div>` : '';
            const preview = (s.content || '').substring(0, 80).replace(/\n/g, ' ');
            const deleted = s.deleted === true;
            return `
            <div class="source-card ${deleted ? 'source-deleted' : ''}" data-idx="${i}">
                <div class="source-card-head">
                    <span class="source-num">${i + 1}</span>
                    <span class="source-name">${escapeHtml(name)}</span>
                    ${deleted ? '<span class="source-deleted-tag">已删除</span>' : ''}
                </div>
                ${hierarchy}
                ${meta ? `<div class="source-chunk">${meta}</div>` : ''}
                ${preview ? `<p class="source-preview">${escapeHtml(preview)}${s.content && s.content.length > 80 ? '...' : ''}</p>` : ''}
            </div>`;
        }).join('') + `</div>`;
    sourcesEl.querySelectorAll('.source-card').forEach((el, idx) => {
        el.addEventListener('click', () => {
            const src = sources[idx];
            if (src.deleted === true) {
                showToast('该文档已被删除', 'info');
                return;
            }
            openSourceModal(src);
        });
    });

    // 鼠标拖拽滑动
    const scroll = sourcesEl.querySelector('.sources-scroll');
    if (scroll) enableDragScroll(scroll);
}

/**
 * 为容器启用鼠标拖拽横向滚动（实时跟随鼠标）
 */
function enableDragScroll(container) {
    let isDown = false, startX = 0, scrollLeft = 0, moved = false;
    container.style.cursor = 'grab';

    container.addEventListener('mousedown', e => {
        isDown = true; moved = false;
        const rect = container.getBoundingClientRect();
        startX = e.clientX - rect.left;
        scrollLeft = container.scrollLeft;
        container.style.cursor = 'grabbing';
        container.style.scrollBehavior = 'auto'; // 拖动时禁用平滑动画，实时跟随
    });

    const endDrag = () => {
        if (!isDown) return;
        isDown = false;
        container.style.cursor = 'grab';
        container.style.scrollBehavior = ''; // 恢复平滑
    };
    container.addEventListener('mouseleave', endDrag);
    container.addEventListener('mouseup', endDrag);

    container.addEventListener('mousemove', e => {
        if (!isDown) return;
        e.preventDefault();
        const rect = container.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const walk = x - startX;
        if (Math.abs(walk) > 3) moved = true;
        container.scrollLeft = scrollLeft - walk;
    });

    // 拖拽后阻止点击
    container.addEventListener('click', e => {
        if (moved) { e.stopPropagation(); e.preventDefault(); }
    }, true);
}

/* ---------- 智能问答 ---------- */
async function askQuestion() {
    const question = $('questionInput').value.trim();
    if (!question) { showToast('请输入问题', 'error'); return; }

    // 如果有正在进行的回答，中断上一个（保留已生成内容，不保存到数据库）
    if (state.abortController) {
        state.abortController.abort();
        await new Promise(r => setTimeout(r, 50));
    }

    $('questionInput').value = '';
    autoResizeTextarea();

    if (!state.sessionId) {
        state.sessionId = genSessionId();
        syncLabels();
    }
    const mySessionId = state.sessionId;

    state.currentQuestion = question;
    state.currentAnswer = '';

    appendUserMessage(question);
    const aiContentEl = appendAIMessage();
    const bubble = aiContentEl.closest('.msg-bubble');
    state.currentAiBubble = bubble;
    const sourcesEl = bubble.querySelector('.msg-sources');
    const statusEl = bubble.querySelector('.msg-status');
    const actionsEl = bubble.querySelector('.msg-actions');
    const stopBtn = bubble.querySelector('.msg-stop-btn');
    $('askBtn').disabled = true;
    actionsEl.classList.remove('hidden');
    stopBtn.classList.remove('hidden');

    state.abortController = new AbortController();
    const myAbortController = state.abortController;
    stopBtn.onclick = () => {
        // 手动终止：保留已生成内容，catch中保存到数据库
        myAbortController.abort();
    };

    let fullAnswer = '';
    let firstToken = true;
    let stopped = false;
    state.currentSources = [];

    try {
        const formData = new URLSearchParams();
        formData.append('question', question);
        formData.append('tenantCode', getTenant());
        formData.append('systemType', getSystem());
        formData.append('userId', getUser());
        formData.append('sessionId', state.sessionId);

        const res = await fetch(`${API_BASE}/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: formData,
            signal: state.abortController.signal
        });
        if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || ('HTTP ' + res.status));
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();

            for (const event of events) {
                const lines = event.split('\n');
                const dataLines = [];
                for (const line of lines) {
                    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
                }
                if (dataLines.length === 0) continue;
                const data = dataLines.join('\n');

                if (data.startsWith('__SESSION__:')) {
                    state.sessionId = data.slice(12);
                    syncLabels();
                } else if (data.startsWith('__SOURCES__:')) {
                    try {
                        state.currentSources = JSON.parse(data.slice(12));
                        renderSourcesInMessage(sourcesEl, state.currentSources);
                    } catch (e) { console.warn('解析来源失败', e); }
                } else {
                    if (firstToken) {
                        aiContentEl.innerHTML = '';
                        aiContentEl.classList.add('typing-cursor');
                        firstToken = false;
                        // 显示回答中状态
                        statusEl.classList.remove('hidden');
                        statusEl.innerHTML = '<span class="status-dot"></span>回答中';
                    }
                    fullAnswer += data;
                    state.currentAnswer = fullAnswer;
                    aiContentEl.innerHTML = renderMarkdown(fullAnswer);
                    scrollToBottom();
                }
            }
        }

        aiContentEl.classList.remove('typing-cursor');
        if (fullAnswer) {
            aiContentEl.innerHTML = renderMarkdown(fullAnswer);
            statusEl.classList.remove('hidden');
            statusEl.innerHTML = '<span class="status-dot status-done"></span>已完成';
            loadHistory();
            // 延迟刷新，等待异步生成的会话标题
            setTimeout(() => loadHistory(), 3000);
        } else {
            aiContentEl.innerHTML = '<span class="text-gray-500">（无返回内容）</span>';
            statusEl.classList.add('hidden');
        }
    } catch (err) {
        if (err.name === 'AbortError') {
            stopped = true;
            aiContentEl.classList.remove('typing-cursor');
            if (!fullAnswer) aiContentEl.innerHTML = '<span class="text-gray-500">（已停止）</span>';
            statusEl.classList.remove('hidden');
            statusEl.innerHTML = '<span class="status-dot status-error"></span>已停止';
            // 新对话触发的终止由newChat保存，此处避免重复保存
            if (!state._abortByNewChat && question) {
                try {
                    await fetch(`${API_BASE}/conversations`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            question: question,
                            answer: fullAnswer || '',
                            userId: getUser(),
                            sessionId: mySessionId,
                            sources: JSON.stringify(state.currentSources || []),
                            tenantCode: getTenant(),
                            systemType: getSystem()
                        })
                    });
                    loadHistory();
                } catch (e) { console.warn('保存终止对话失败', e); }
            }
            state._abortByNewChat = false;
        } else {
            aiContentEl.classList.remove('typing-cursor');
            aiContentEl.innerHTML = '<span class="text-red-400">查询失败: ' + escapeHtml(err.message) + '</span>';
            statusEl.classList.remove('hidden');
            statusEl.innerHTML = '<span class="status-dot status-error"></span>回答失败';
            showToast('查询失败: ' + err.message, 'error');
        }
    } finally {
        $('askBtn').disabled = false;
        stopBtn.classList.add('hidden');
        // 只有当前abortController仍是自己的才清除状态（新对话已替换时不清除）
        if (state.abortController === myAbortController) {
            state.abortController = null;
            state.currentQuestion = '';
            state.currentAnswer = '';
            state.currentAiBubble = null;
        }
        scrollToBottom();
    }
}

/**
 * 新对话：清空聊天区，重置会话
 */
function newChat() {
    // 如果有正在进行的回答，先终止并保存当前内容（避免丢失）
    if (state.abortController) {
        const q = state.currentQuestion;
        const a = state.currentAnswer;
        const s = state.currentSources;
        const sid = state.sessionId;
        state._abortByNewChat = true;
        state.abortController.abort();
        if (q) {
            fetch(`${API_BASE}/conversations`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    question: q, answer: a || '',
                    userId: getUser(), sessionId: sid,
                    sources: JSON.stringify(s || []),
                    tenantCode: getTenant(), systemType: getSystem()
                })
            }).then(() => loadHistory()).catch(e => console.warn('新对话前保存失败', e));
        }
    }
    if (state.sessionId) state.draftMap[state.sessionId] = $('questionInput').value;
    state.sessionId = null;
    state.currentTitle = '';
    state.currentSources = [];
    state.currentQuestion = '';
    state.currentAnswer = '';
    $('questionInput').value = '';
    clearChat();
    syncLabels();
    switchTab('search');
    loadHistory();
    showToast('已开启新对话', 'success');
}

/**
 * 输入框自适应高度
 */
function autoResizeTextarea() {
    const ta = $('questionInput');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 128) + 'px';
}

$('askBtn').addEventListener('click', askQuestion);
$('newChatBtn').addEventListener('click', newChat);
$('questionInput').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); askQuestion(); }
});
$('questionInput').addEventListener('input', autoResizeTextarea);
// 全局快捷键
document.addEventListener('keydown', e => {
    if (e.ctrlKey && e.key === 'n') { e.preventDefault(); newChat(); }
    if (e.ctrlKey && e.key === 'k') { e.preventDefault(); $('questionInput').focus(); }
});

// 历史对话管理
$('historyManageBtn').addEventListener('click', () => {
    state.historyBatchMode = !state.historyBatchMode;
    state.historySelected.clear();
    renderHistory();
});
$('historySelectAll').addEventListener('change', (e) => {
    const recent = (state.history || []).slice(0, 5);
    if (e.target.checked) {
        recent.forEach(s => state.historySelected.add(s.sessionId));
    } else {
        recent.forEach(s => state.historySelected.delete(s.sessionId));
    }
    renderHistory();
});
$('historyBatchDelete').addEventListener('click', batchDeleteHistory);

/* ---------- 知识库来源弹窗 ---------- */
function openSourceModal(src) {
    $('sourceModalTitle').textContent = src.title || src.fileName || '来源详情';
    const page = src.pageNumber != null && src.pageNumber !== '' ? '第 ' + src.pageNumber + ' 页' : '-';
    const chunk = src.chunkIndex != null ? '第 ' + src.chunkIndex + ' 段' : '-';
    $('sourceModalMeta').innerHTML = `
        <div class="flex flex-wrap gap-x-5 gap-y-1">
            <span><span class="text-gray-600">文件</span> <span class="text-gray-400">${escapeHtml(src.fileName || '-')}</span></span>
            <span><span class="text-gray-600">页码</span> <span class="text-primary-400 font-medium">${page}</span></span>
            <span><span class="text-gray-600">分片</span> <span class="text-gray-400">${chunk}</span></span>
        </div>
        ${src.titleHierarchy ? `<div class="mt-1.5 text-gray-500"><span class="text-gray-600">层级</span> ${escapeHtml(src.titleHierarchy)}</div>` : ''}
    `;
    $('sourceModalContent').innerHTML = `<div class="text-xs text-gray-500 mb-2">引用内容</div><div class="text-sm text-gray-300 whitespace-pre-wrap leading-7">${escapeHtml(src.content || '')}</div>`;
    $('sourceModal').classList.remove('hidden');
    $('sourceModal').classList.add('flex');
}

$('sourceModalClose').addEventListener('click', () => {
    $('sourceModal').classList.add('hidden');
    $('sourceModal').classList.remove('flex');
});
$('sourceModal').addEventListener('click', e => {
    if (e.target.id === 'sourceModal') {
        $('sourceModal').classList.add('hidden');
        $('sourceModal').classList.remove('flex');
    }
});

/* ---------- 提问历史（按会话分组） ---------- */
async function loadHistory() {
    try {
        const params = new URLSearchParams();
        params.append('userId', getUser());
        params.append('tenantCode', getTenant());
        params.append('systemType', getSystem());
        params.append('limit', '20');
        const res = await fetch(`${API_BASE}/sessions?${params.toString()}`);
        if (res.ok) {
            state.history = await res.json();
            renderHistory();
        }
    } catch (err) {
        console.warn('加载会话列表失败:', err.message);
    }
}

function renderHistory() {
    const list = $('historyList');
    const count = state.history ? state.history.length : 0;
    $('historyCount').textContent = count;

    // 批量管理栏显隐
    $('historyBatchBar').classList.toggle('hidden', !state.historyBatchMode);
    updateBatchDeleteBtn();

    if (count === 0) {
        list.innerHTML = '<p class="text-xs text-gray-600 px-2 py-1">暂无历史</p>';
        return;
    }
    // 只显示最近 5 条
    const recent = state.history.slice(0, 5);
    list.innerHTML = recent.map((s, i) => {
        const selected = state.historySelected.has(s.sessionId);
        const displayName = s.title || s.question || '新对话';
        return `
        <div class="history-item group" data-idx="${i}" data-session="${escapeHtml(s.sessionId)}" title="${escapeHtml(displayName)}">
            ${state.historyBatchMode ? `
                <input type="checkbox" class="history-check accent-primary-500 shrink-0" ${selected ? 'checked' : ''}>
            ` : ''}
            <div class="flex-1 min-w-0">
                <p class="text-xs text-gray-300 truncate">${escapeHtml(displayName)}</p>
                <p class="text-[10px] text-gray-600 mt-0.5">${formatTime(s.createTime)}</p>
            </div>
            ${state.historyBatchMode ? '' : `
                <div class="history-actions opacity-0 group-hover:opacity-100 flex items-center gap-0.5 shrink-0">
                    <button class="history-rename-btn text-gray-500 hover:text-primary-400 transition" title="重命名">
                        <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/></svg>
                    </button>
                    <button class="history-export-btn text-gray-500 hover:text-green-400 transition" title="导出">
                        <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
                    </button>
                    <button class="history-delete-btn text-gray-500 hover:text-red-400 transition" title="删除">
                        <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                    </button>
                </div>
            `}
        </div>`;
    }).join('');

    // 点击加载会话（非批量模式）
    if (!state.historyBatchMode) {
        list.querySelectorAll('.history-item').forEach(el => {
            el.addEventListener('click', (e) => {
                if (e.target.closest('.history-actions')) return;
                const idx = parseInt(el.dataset.idx);
                const s = recent[idx];
                switchTab('search');
                loadSession(s.sessionId);
            });
        });
        // 重命名 - 调用后端接口更新数据库
        list.querySelectorAll('.history-rename-btn').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const item = btn.closest('.history-item');
                const sessionId = item.dataset.session;
                const oldName = item.querySelector('p').textContent;
                const newName = prompt('输入对话名称:', oldName);
                if (newName !== null && newName.trim()) {
                    try {
                        const res = await fetch(`${API_BASE}/conversations/session/${encodeURIComponent(sessionId)}/title`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ title: newName.trim() })
                        });
                        if (res.ok) {
                            // 更新历史列表中的标题
                            const hi = state.history.find(s => s.sessionId === sessionId);
                            if (hi) { hi.title = newName.trim(); hi.question = newName.trim(); }
                            // 如果是当前打开的会话，同步更新显示
                            if (state.sessionId === sessionId) {
                                state.currentTitle = newName.trim();
                                syncLabels();
                            }
                            renderHistory();
                            showToast('已重命名', 'success');
                        } else {
                            showToast('重命名失败', 'error');
                        }
                    } catch (err) {
                        showToast('重命名失败: ' + err.message, 'error');
                    }
                }
            });
        });
        // 导出 - 选择格式
        list.querySelectorAll('.history-export-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const item = btn.closest('.history-item');
                const sessionId = item.dataset.session;
                const title = item.querySelector('p').textContent;
                showExportMenu(btn, sessionId, title);
            });
        });
        // 单条删除 - 内联确认
        list.querySelectorAll('.history-delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const item = btn.closest('.history-item');
                const sessionId = item.dataset.session;
                showInlineDeleteConfirm(btn, sessionId);
            });
        });
    } else {
        // 批量模式：checkbox 选择
        list.querySelectorAll('.history-check').forEach(cb => {
            cb.addEventListener('change', () => {
                const item = cb.closest('.history-item');
                const sessionId = item.dataset.session;
                if (cb.checked) state.historySelected.add(sessionId);
                else state.historySelected.delete(sessionId);
                updateBatchDeleteBtn();
            });
        });
    }
}

function updateBatchDeleteBtn() {
    const btn = $('historyBatchDelete');
    btn.disabled = state.historySelected.size === 0;
    btn.textContent = `删除选中(${state.historySelected.size})`;
    // 全选状态
    const recent = (state.history || []).slice(0, 5);
    const allSelected = recent.length > 0 && recent.every(s => state.historySelected.has(s.sessionId));
    $('historySelectAll').checked = allSelected;
}

/**
 * 在删除按钮位置显示内联确认提示
 */
function showInlineDeleteConfirm(btn, sessionId) {
    const actions = btn.closest('.history-actions');
    const wrapper = document.createElement('div');
    wrapper.className = 'inline-delete-confirm';
    wrapper.innerHTML = `
        <button class="idc-yes" title="确认删除">删除</button>
        <button class="idc-no" title="取消">取消</button>
    `;
    // 隐藏重命名和导出按钮，给确认按钮腾出空间
    if (actions) {
        actions.querySelectorAll('button:not(.history-delete-btn)').forEach(b => b.style.display = 'none');
    }
    btn.replaceWith(wrapper);
    wrapper.querySelector('.idc-yes').addEventListener('click', (e) => {
        e.stopPropagation();
        deleteHistorySession(sessionId);
    });
    wrapper.querySelector('.idc-no').addEventListener('click', (e) => {
        e.stopPropagation();
        wrapper.replaceWith(btn);
        if (actions) {
            actions.querySelectorAll('button').forEach(b => b.style.display = '');
        }
    });
}

/**
 * 显示导出格式选择菜单
 */
function showExportMenu(anchor, sessionId, title) {
    // 移除已存在的菜单
    document.querySelectorAll('.export-menu').forEach(m => m.remove());
    const menu = document.createElement('div');
    menu.className = 'export-menu fixed z-50 bg-gray-800 border border-gray-700 rounded-lg shadow-xl py-1 text-xs';
    menu.style.minWidth = '120px';
    menu.innerHTML = `
        <div class="px-3 py-1.5 hover:bg-gray-700 cursor-pointer text-gray-200" data-format="md">导出 Markdown</div>
        <div class="px-3 py-1.5 hover:bg-gray-700 cursor-pointer text-gray-200" data-format="pdf">导出 PDF</div>`;
    document.body.appendChild(menu);
    const rect = anchor.getBoundingClientRect();
    menu.style.left = (rect.right - 120) + 'px';
    menu.style.top = (rect.bottom + 4) + 'px';
    menu.querySelectorAll('[data-format]').forEach(item => {
        item.addEventListener('click', () => {
            menu.remove();
            if (item.dataset.format === 'pdf') {
                exportConversationPDF(sessionId, title);
            } else {
                exportConversation(sessionId, title);
            }
        });
    });
    setTimeout(() => {
        document.addEventListener('click', function closeMenu(e) {
            if (!menu.contains(e.target)) { menu.remove(); document.removeEventListener('click', closeMenu); }
        });
    }, 10);
}

/**
 * 导出对话为 Markdown 文件
 */
async function exportConversation(sessionId, title) {
    try {
        let md = `# ${title || '对话记录'}\n\n`;
        let offset = 0;
        const pageSize = 50;
        let total = 0;
        // 分页正序查询，依次写出
        while (true) {
            const res = await fetch(`${API_BASE}/conversations/session?sessionId=${encodeURIComponent(sessionId)}&limit=${pageSize}&offset=${offset}&order=asc`);
            if (!res.ok) { showToast('导出失败', 'error'); return; }
            const messages = await res.json();
            if (!messages || messages.length === 0) break;
            messages.forEach(m => {
                md += `## 用户\n${m.question || ''}\n\n`;
                md += `## AI\n${m.answer || ''}\n\n`;
                let srcs = m.sources;
                if (typeof srcs === 'string') { try { srcs = JSON.parse(srcs); } catch { srcs = null; } }
                if (srcs && Array.isArray(srcs) && srcs.length) {
                    md += `**引用来源:**\n`;
                    srcs.forEach((s, i) => { md += `${i + 1}. ${s.title || s.fileName || '未命名'} (第${s.pageNumber || '-'}页)\n`; });
                    md += '\n';
                }
                md += `---\n\n`;
            });
            total += messages.length;
            if (messages.length < pageSize) break;
            offset += pageSize;
        }
        const blob = new Blob([md], { type: 'text/markdown' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `${title || 'conversation'}.md`;
        a.click();
        URL.revokeObjectURL(a.href);
        showToast(`已导出 ${total} 条对话`, 'success');
    } catch (err) {
        showToast('导出失败: ' + err.message, 'error');
    }
}

/**
 * 导出对话为 PDF（通过浏览器打印，Markdown渲染为HTML）
 */
async function exportConversationPDF(sessionId, title) {
    try {
        showToast('正在生成PDF...', 'info');
        let html = `<div style="font-family:-apple-system,'Segoe UI',sans-serif;max-width:800px;margin:0 auto;padding:20px;color:#333;">
            <h1 style="border-bottom:2px solid #6366f1;padding-bottom:10px;">${escapeHtml(title || '对话记录')}</h1>`;
        let offset = 0;
        const pageSize = 50;
        let total = 0;
        while (true) {
            const res = await fetch(`${API_BASE}/conversations/session?sessionId=${encodeURIComponent(sessionId)}&limit=${pageSize}&offset=${offset}&order=asc`);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const messages = await res.json();
            if (!messages || messages.length === 0) break;
            messages.forEach(m => {
                html += `<div style="margin:16px 0;">
                    <div style="background:#eef2ff;border-radius:8px;padding:10px 14px;margin-bottom:6px;">
                        <strong>用户：</strong>${escapeHtml(m.question || '')}
                    </div>
                    <div style="background:#f9fafb;border-radius:8px;padding:10px 14px;">
                        <strong>AI：</strong>${renderMarkdown(m.answer || '')}
                    </div>`;
                let srcs = m.sources;
                if (typeof srcs === 'string') { try { srcs = JSON.parse(srcs); } catch { srcs = null; } }
                if (srcs && Array.isArray(srcs) && srcs.length) {
                    html += `<div style="font-size:12px;color:#666;margin-top:6px;padding-left:14px;"><strong>引用来源：</strong>`;
                    srcs.forEach((s, i) => { html += `${i + 1}. ${escapeHtml(s.title || s.fileName || '未命名')} (第${s.pageNumber || '-'}页) `; });
                    html += `</div>`;
                }
                html += `</div>`;
            });
            total += messages.length;
            if (messages.length < pageSize) break;
            offset += pageSize;
        }
        html += `</div>`;
        // 打开打印窗口
        const win = window.open('', '_blank');
        win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>${escapeHtml(title || '对话记录')}</title>
            <style>
                body{margin:0;padding:20px;}
                h1{font-size:20px;}
                pre{background:#f4f4f5;padding:10px;border-radius:6px;overflow-x:auto;}
                code{background:#f4f4f5;padding:2px 4px;border-radius:3px;}
                table{border-collapse:collapse;width:100%;}
                th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}
                th{background:#f4f4f5;}
                blockquote{border-left:3px solid #6366f1;margin:0;padding-left:12px;color:#666;}
                @media print{body{padding:0;}}
            </style></head><body>${html}
            <script>window.onload=function(){setTimeout(function(){window.print();},300);};<\/script>
            </body></html>`);
        win.document.close();
        showToast(`已导出 ${total} 条对话为 PDF`, 'success');
    } catch (err) {
        showToast('PDF导出失败: ' + err.message, 'error');
    }
}

async function deleteHistorySession(sessionId) {
    try {
        const res = await fetch(`${API_BASE}/conversations/session/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
        if (res.ok) {
            showToast('对话已删除', 'success');
            state.historySelected.delete(sessionId);
            if (state.sessionId === sessionId) {
                state.sessionId = null;
                clearChat();
                syncLabels();
            }
            loadHistory();
        } else {
            showToast('删除失败', 'error');
        }
    } catch (err) {
        showToast('删除失败: ' + err.message, 'error');
    }
}

async function batchDeleteHistory() {
    if (state.historySelected.size === 0) return;
    if (!confirm(`确定删除选中的 ${state.historySelected.size} 条对话记录？`)) return;
    try {
        const res = await fetch(`${API_BASE}/conversations/batch`, {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify([...state.historySelected])
        });
        if (res.ok) {
            showToast(`已删除 ${state.historySelected.size} 条对话`, 'success');
            const currentDeleted = state.historySelected.has(state.sessionId);
            state.historySelected.clear();
            if (currentDeleted) {
                state.sessionId = null;
                clearChat();
                syncLabels();
            }
            loadHistory();
        } else {
            showToast('批量删除失败', 'error');
        }
    } catch (err) {
        showToast('批量删除失败: ' + err.message, 'error');
    }
}

/**
 * 加载某个会话的完整对话线程
 */
async function loadSession(sessionId) {
    if (!sessionId) return;
    if (state.sessionId) state.draftMap[state.sessionId] = $('questionInput').value;
    state.sessionId = sessionId;
    state.msgOffset = 0;
    state.msgHasMore = true;
    // 从历史列表中查找该会话的标题
    const histItem = state.history.find(s => s.sessionId === sessionId);
    state.currentTitle = histItem ? (histItem.title || histItem.question || '') : '';
    syncLabels();
    clearChat();
    $('chatMessages').innerHTML = '<div class="text-center text-gray-500 text-sm py-8">加载对话中...</div>';

    try {
        const res = await fetch(`${API_BASE}/conversations/session?sessionId=${encodeURIComponent(sessionId)}&limit=10&offset=0`);
        if (res.ok) {
            const messages = await res.json();
            // 后端倒序返回，反转后正序显示
            const asc = (messages || []).slice().reverse();
            state.msgHasMore = (messages || []).length >= 10;
            renderConversationThread(asc);
            $('questionInput').value = state.draftMap[sessionId] || '';
            autoResizeTextarea();
        }
    } catch (err) {
        $('chatMessages').innerHTML = '<div class="text-center text-red-400 text-sm py-8">加载失败: ' + escapeHtml(err.message) + '</div>';
    }
}

/**
 * 向上滚动加载更早的对话记录
 */
async function loadOlderMessages() {
    if (state.msgLoading || !state.msgHasMore || !state.sessionId) return;
    state.msgLoading = true;
    state.msgOffset += 10;
    try {
        const res = await fetch(`${API_BASE}/conversations/session?sessionId=${encodeURIComponent(state.sessionId)}&limit=10&offset=${state.msgOffset}`);
        if (res.ok) {
            const messages = await res.json();
            if (!messages || messages.length === 0) {
                state.msgHasMore = false;
            } else {
                const asc = messages.slice().reverse();
                prependOlderMessages(asc);
                state.msgHasMore = messages.length >= 10;
            }
        }
    } catch (err) {
        console.warn('加载更早对话失败:', err.message);
    } finally {
        state.msgLoading = false;
    }
}

/**
 * 将更早的消息插入到聊天区顶部，保持滚动位置
 */
function prependOlderMessages(messages) {
    const container = $('chatMessages');
    const prevScrollHeight = container.scrollHeight;
    const prevScrollTop = container.scrollTop;
    // 临时容器构建节点
    const frag = document.createDocumentFragment();
    messages.forEach(m => {
        frag.appendChild(buildUserMessage(m));
        const aiRow = buildAIMessage(m);
        if (aiRow) frag.appendChild(aiRow);
    });
    container.insertBefore(frag, container.firstChild);
    // 保持滚动位置（跳到加载前的位置）
    container.scrollTop = container.scrollHeight - prevScrollHeight + prevScrollTop;
}

function buildUserMessage(m) {
    const row = document.createElement('div');
    row.className = 'msg-row user';
    row.innerHTML = `<div class="msg-avatar">U</div><div class="msg-bubble">${escapeHtml(m.question || '')}</div>`;
    return row;
}

function buildAIMessage(m) {
    // AI回答为空时不显示（如对话中断只保存了用户提问）
    if (!m.answer || !m.answer.trim()) return null;
    const row = document.createElement('div');
    row.className = 'msg-row ai';
    row.innerHTML = `
        <div class="msg-avatar">AI</div>
        <div class="msg-bubble">
            <div class="ai-content">${renderMarkdown(m.answer || '')}</div>
            <div class="msg-sources hidden"></div>
        </div>`;
    if (m.sources) {
        let srcs = m.sources;
        if (typeof srcs === 'string') { try { srcs = JSON.parse(srcs); } catch (e) { srcs = null; } }
        if (srcs && Array.isArray(srcs) && srcs.length) {
            renderSourcesInMessage(row.querySelector('.msg-sources'), srcs);
        }
    }
    return row;
}

/**
 * 渲染多轮对话线程到聊天区
 */
function renderConversationThread(messages) {
    if (!messages || messages.length === 0) {
        clearChat();
        return;
    }
    $('chatMessages').innerHTML = '';
    messages.forEach((m, idx) => {
        // 用户消息
        const userRow = document.createElement('div');
        userRow.className = 'msg-row user';
        userRow.innerHTML = `<div class="msg-avatar">U</div><div class="msg-bubble">${escapeHtml(m.question || '')}</div>`;
        $('chatMessages').appendChild(userRow);

        // AI 消息（回答为空时不显示，如对话中断只保存了用户提问）
        if (m.answer && m.answer.trim()) {
            const aiRow = document.createElement('div');
            aiRow.className = 'msg-row ai';
            aiRow.innerHTML = `
                <div class="msg-avatar">AI</div>
                <div class="msg-bubble">
                    <div class="ai-content">${renderMarkdown(m.answer || '')}</div>
                    <div class="msg-sources hidden"></div>
                </div>`;
            $('chatMessages').appendChild(aiRow);

            // 每轮都显示来源，标注"本轮引用"
            if (m.sources) {
                let srcs = m.sources;
                if (typeof srcs === 'string') { try { srcs = JSON.parse(srcs); } catch (e) { srcs = null; } }
                if (srcs && Array.isArray(srcs) && srcs.length) {
                    const sourcesEl = aiRow.querySelector('.msg-sources');
                    renderSourcesInMessage(sourcesEl, srcs);
                }
            }
        }
    });
    scrollToBottom();
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

/* ---------- 文件上传 ---------- */
const dropZone = $('dropZone');
const fileInput = $('fileInput');

dropZone.addEventListener('click', () => fileInput.click());
dropZone.addEventListener('dragover', e => { e.preventDefault(); dropZone.classList.add('dragover'); });
dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
dropZone.addEventListener('drop', e => {
    e.preventDefault();
    dropZone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) selectFiles(e.dataTransfer.files);
});
fileInput.addEventListener('change', e => {
    if (e.target.files.length > 0) selectFiles(e.target.files);
});

function selectFiles(files) {
    state.selectedFiles = Array.from(files);
    $('selectedFile').classList.remove('hidden');
    const names = state.selectedFiles.map(f => f.name).join(', ');
    $('selectedFileName').textContent = state.selectedFiles.length > 1
        ? `${state.selectedFiles.length} 个文件：${names}` : names;
}

$('clearFile').addEventListener('click', () => {
    state.selectedFiles = [];
    fileInput.value = '';
    $('selectedFile').classList.add('hidden');
});

async function uploadFile() {
    if (!state.selectedFiles || state.selectedFiles.length === 0) { showToast('请先选择文件', 'error'); return; }

    const btn = $('uploadBtn');
    const btnText = $('uploadBtnText');
    btn.disabled = true;
    const total = state.selectedFiles.length;
    let successCount = 0;

    for (let i = 0; i < total; i++) {
        const file = state.selectedFiles[i];
        btnText.textContent = `上传中 ${i + 1}/${total}...`;

        const formData = new FormData();
        formData.append('file', file);
        formData.append('title', total === 1 ? $('uploadTitle').value.trim() : file.name.replace(/\.[^.]+$/, ''));
        formData.append('description', $('uploadDesc').value.trim());
        formData.append('docCode', total === 1 ? $('uploadDocCode').value.trim() : '');
        formData.append('tenantCode', $('uploadTenant').value.trim() || 'default');
        formData.append('systemType', $('uploadSystem').value.trim() || 'default');
        formData.append('userId', getUser());

        try {
            const res = await fetch(`${API_BASE}/upload`, { method: 'POST', body: formData });
            const data = await res.json();
            if (data.status === 'SUCCESS' || data.status === 'PROCESSING') {
                successCount++;
                if (data.status === 'PROCESSING' && data.docCode) pollDocumentStatus(data.docCode);
            }
        } catch (err) {
            showToast(`${file.name} 上传失败: ${err.message}`, 'error');
        }
    }

    showToast(`成功上传 ${successCount}/${total} 个文档`, successCount > 0 ? 'success' : 'error');
    clearUploadForm();
    updateStats();
    btn.disabled = false;
    btnText.textContent = '上传并入库';
    // 关闭弹窗，跳转到文档管理页
    closeUploadModal();
    if (successCount > 0) switchTab('documents');
}

$('uploadBtn').addEventListener('click', uploadFile);

/* ---------- 上传弹窗 ---------- */
function openUploadModal() {
    $('uploadTenant').value = getTenant();
    $('uploadSystem').value = getSystem();
    $('urlTenant').value = getTenant();
    $('urlSystem').value = getSystem();
    $('uploadModal').classList.remove('hidden');
    $('uploadModal').classList.add('flex');
}
function closeUploadModal() {
    $('uploadModal').classList.add('hidden');
    $('uploadModal').classList.remove('flex');
}
$('fabUpload').addEventListener('click', openUploadModal);
$('uploadModalClose').addEventListener('click', closeUploadModal);

/**
 * 轮询文档处理状态，直到 SUCCESS 或 FAILED
 */
function pollDocumentStatus(docCode) {
    let attempts = 0;
    const maxAttempts = 20; // 最多轮询 10 分钟（20 * 30s）
    const interval = setInterval(async () => {
        attempts++;
        if (attempts > maxAttempts) { clearInterval(interval); return; }
        try {
            const res = await fetch(`${API_BASE}/list?tenantCode=${encodeURIComponent(getTenant())}&systemType=${encodeURIComponent(getSystem())}`);
            const docs = await res.json();
            const doc = (docs || []).find(d => d.docCode === docCode);
            if (doc) {
                // 每次轮询都刷新文档列表，同步状态、分片数、大小等全部信息
                if (!$('tab-documents').classList.contains('hidden')) loadDocuments();
                if (doc.status === 'SUCCESS') {
                    clearInterval(interval);
                    showToast(`文档「${doc.fileName || doc.title}」处理完成`, 'success');
                    updateStats();
                } else if (doc.status === 'FAILED') {
                    clearInterval(interval);
                    showToast(`文档处理失败: ${doc.errorMessage || '未知错误'}`, 'error');
                }
            }
        } catch { /* 静默重试 */ }
    }, 30000);
}

function clearUploadForm() {
    state.selectedFiles = [];
    fileInput.value = '';
    $('selectedFile').classList.add('hidden');
    $('uploadTitle').value = '';
    $('uploadDesc').value = '';
    $('uploadDocCode').value = '';
}

/* ---------- URL 上传 ---------- */
async function uploadByUrl() {
    const url = $('urlInput').value.trim();
    if (!url) { showToast('请输入文件 URL', 'error'); return; }

    const btn = $('urlUploadBtn');
    const btnText = $('urlUploadBtnText');
    btn.disabled = true;
    btnText.textContent = '下载中...';

    const payload = {
        url,
        title: $('urlTitle').value.trim(),
        description: $('urlDesc').value.trim(),
        docCode: $('urlDocCode').value.trim(),
        tenantCode: $('urlTenant').value.trim() || 'default',
        systemType: $('urlSystem').value.trim() || 'default'
    };

    try {
        const res = await fetch(`${API_BASE}/upload-by-url`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (data.status === 'SUCCESS' || data.status === 'PROCESSING') {
            showToast('URL 上传成功', 'success');
            $('urlInput').value = '';
            $('urlTitle').value = '';
            $('urlDesc').value = '';
            $('urlDocCode').value = '';
            updateStats();
            if (data.status === 'PROCESSING' && data.docCode) pollDocumentStatus(data.docCode);
            closeUploadModal();
            switchTab('documents');
        } else {
            showToast('上传失败: ' + (data.errorMessage || '未知错误'), 'error');
        }
    } catch (err) {
        showToast('请求失败: ' + err.message, 'error');
    } finally {
        btn.disabled = false;
        btnText.textContent = '下载并入库';
    }
}

$('urlUploadBtn').addEventListener('click', uploadByUrl);

/* ---------- 文档列表 ---------- */
async function loadDocuments() {
    const loading = $('docsLoading');
    const empty = $('docsEmpty');
    const list = $('docsList');

    loading.classList.remove('hidden');
    empty.classList.add('hidden');
    list.innerHTML = '';

    try {
        const res = await fetch(`${API_BASE}/list?tenantCode=${encodeURIComponent(getTenant())}&systemType=${encodeURIComponent(getSystem())}`);
        let docs = await res.json();
        loading.classList.add('hidden');

        // 前端筛选（搜索 + 状态）
        const kw = (state.docSearch || '').toLowerCase();
        const sf = state.docStatusFilter || 'all';
        docs = (docs || []).filter(d => {
            if (sf !== 'all' && d.status !== sf) return false;
            if (kw && !(d.fileName || '').toLowerCase().includes(kw) && !(d.title || '').toLowerCase().includes(kw)) return false;
            return true;
        });

        if (!docs || docs.length === 0) {
            empty.classList.remove('hidden');
            empty.querySelector('p').textContent = kw || sf !== 'all' ? '没有匹配的文档' : '暂无文档，请先上传';
            return;
        }

        list.innerHTML = docs.map((doc, i) => {
            const canDelete = (doc.status === 'SUCCESS' || doc.status === 'FAILED')
                && doc.userId && doc.userId === getUser();
            const canRetry = doc.status === 'FAILED' && doc.userId && doc.userId === getUser();
            const uploaderLabel = doc.userId ? `上传者: ${escapeHtml(doc.userId)}` : '上传者: 未知';
            const statusClass = doc.status === 'SUCCESS' ? 'tag-green' :
                doc.status === 'PROCESSING' ? 'tag-yellow' :
                doc.status === 'FAILED' ? 'tag-red' : 'tag-gray';
            const statusText = doc.status === 'SUCCESS' ? '已完成' :
                doc.status === 'PROCESSING' ? '处理中' :
                doc.status === 'FAILED' ? '处理失败' : (doc.status || '-');
            const statusSpinner = doc.status === 'PROCESSING' ? '<span class="spinner-sm"></span>' : '';
            return `
            <div class="doc-card cursor-pointer" data-idx="${i}" data-doc-code="${escapeHtml(doc.docCode || '')}" data-doc-name="${escapeHtml(doc.title || doc.fileName || '')}" data-title="${escapeHtml(doc.title || '')}" data-desc="${escapeHtml(doc.description || '')}" data-tenant="${escapeHtml(doc.tenantCode || '')}" data-system="${escapeHtml(doc.systemType || '')}">
                <div class="flex items-start gap-3 mb-3">
                    <div class="w-10 h-10 rounded-lg bg-primary-500/15 flex items-center justify-center shrink-0">
                        <svg class="w-5 h-5 text-primary-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/></svg>
                    </div>
                    <div class="flex-1 min-w-0">
                        <h3 class="text-sm font-semibold text-gray-100 truncate" title="${escapeHtml(doc.title || doc.fileName || '')}">${escapeHtml(doc.title || doc.fileName || '未命名')}</h3>
                        <p class="text-xs text-gray-500 mt-0.5 truncate" title="${escapeHtml(doc.description || doc.fileName || '')}">${escapeHtml(doc.description || doc.fileName || '')}</p>
                    </div>
                    <span class="tag ${statusClass} shrink-0 inline-flex items-center gap-1.5 text-xs px-2.5 py-1">${statusSpinner}${statusText}</span>
                    <div class="doc-actions flex items-center gap-1.5 shrink-0">
                        ${canRetry ? `<button class="doc-retry-btn w-9 h-9 rounded-lg bg-yellow-500/10 hover:bg-yellow-500/20 text-yellow-400 flex items-center justify-center transition" title="重新上传">
                            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
                        </button>` : ''}
                        ${canDelete ? `<button class="doc-delete-btn w-9 h-9 rounded-lg bg-red-500/10 hover:bg-red-500/20 text-red-400 flex items-center justify-center transition" data-doc-code="${escapeHtml(doc.docCode || '')}" data-file-name="${escapeHtml(doc.fileName || '')}" title="删除文档">
                            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                        </button>` : ''}
                    </div>
                </div>
                ${doc.status === 'FAILED' && doc.errorMessage ? `<p class="text-xs text-red-400 mb-3 bg-red-500/10 rounded-lg px-3 py-2">处理失败: ${escapeHtml(doc.errorMessage)}</p>` : ''}
                <div class="grid grid-cols-3 gap-2 text-xs">
                    <div><span class="text-gray-500">分片</span> <span class="text-primary-400 font-semibold">${doc.chunkCount || 0}</span></div>
                    <div><span class="text-gray-500">大小</span> <span class="text-gray-300">${formatSize(doc.fileSize)}</span></div>
                    <div><span class="text-gray-500">时间</span> <span class="text-gray-300">${formatTime(doc.uploadTime)}</span></div>
                </div>
                <div class="flex items-center gap-2 mt-3 pt-3 border-t border-white/5 flex-wrap">
                    <span class="tag tag-gray">${uploaderLabel}</span>
                    ${doc.docCode ? `<span class="tag tag-blue">编码: ${escapeHtml(doc.docCode)}</span>` : ''}
                    ${doc.tenantCode ? `<span class="tag tag-purple">租户: ${escapeHtml(doc.tenantCode)}</span>` : ''}
                    ${doc.systemType ? `<span class="tag tag-gray">系统: ${escapeHtml(doc.systemType)}</span>` : ''}
                </div>
            </div>`;
        }).join('');

        // 绑定：点击卡片预览、删除、重传
        list.querySelectorAll('.doc-card').forEach(card => {
            card.addEventListener('click', (e) => {
                if (e.target.closest('.doc-delete-btn') || e.target.closest('.doc-retry-btn')
                    || e.target.closest('.doc-actions') || e.target.closest('.tag')) return;
                showDocPreview(card.dataset.docCode, card.dataset.docName);
            });
        });
        list.querySelectorAll('.doc-delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const card = btn.closest('.doc-card');
                const docCode = btn.dataset.docCode;
                const fileName = btn.dataset.fileName;
                const docName = card.querySelector('h3').textContent;
                deleteDocument(docCode, fileName, docName, card);
            });
        });
        // 重传按钮：跳转到上传页并预填 docCode
        list.querySelectorAll('.doc-retry-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const card = btn.closest('.doc-card');
                openUploadModal();
                $('uploadTitle').value = card.dataset.title || '';
                $('uploadDesc').value = card.dataset.desc || '';
                $('uploadDocCode').value = card.dataset.docCode || '';
                $('uploadTenant').value = card.dataset.tenant || getTenant();
                $('uploadSystem').value = card.dataset.system || getSystem();
                showToast('请重新选择文件上传', 'info');
            });
        });
    } catch (err) {
        loading.classList.add('hidden');
        showToast('加载文档列表失败: ' + err.message, 'error');
    }

    // 有处理中的文档时，5秒后自动刷新
    if (state.docsRefreshTimer) { clearTimeout(state.docsRefreshTimer); state.docsRefreshTimer = null; }
    const hasProcessing = (docs || []).some(d => d.status === 'PROCESSING');
    if (hasProcessing && !$('tab-documents').classList.contains('hidden')) {
        state.docsRefreshTimer = setTimeout(() => loadDocuments(), 5000);
    }
}

$('refreshDocs').addEventListener('click', loadDocuments);
$('docSearch').addEventListener('input', e => { state.docSearch = e.target.value; loadDocuments(); });
$('docStatusFilter').addEventListener('change', e => { state.docStatusFilter = e.target.value; loadDocuments(); });

/**
 * 文档预览：复用来源弹窗展示文档基本信息
 */
function showDocPreview(docCode, docName) {
    if (!docCode) { showToast('该文档无编码', 'error'); return; }
    fetch(`${API_BASE}/list?tenantCode=${encodeURIComponent(getTenant())}&systemType=${encodeURIComponent(getSystem())}`)
        .then(r => r.json()).then(docs => {
            const doc = (docs || []).find(d => d.docCode === docCode);
            if (!doc) { showToast('未找到文档', 'error'); return; }
            const statusText = doc.status === 'SUCCESS' ? '已完成' : doc.status === 'PROCESSING' ? '处理中' : doc.status === 'FAILED' ? '处理失败' : (doc.status || '-');
            const statusClass = doc.status === 'SUCCESS' ? 'text-green-400' : doc.status === 'PROCESSING' ? 'text-yellow-400' : doc.status === 'FAILED' ? 'text-red-400' : 'text-gray-400';
            const displayTitle = doc.title || doc.fileName || '未命名';
            $('sourceModalTitle').textContent = displayTitle;
            $('sourceModalMeta').innerHTML = `
                <div class="grid grid-cols-2 gap-x-6 gap-y-2.5">
                    <div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">状态</span><span class="${statusClass} text-xs font-medium">${statusText}</span></div>
                    <div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">分片数</span><span class="text-primary-400 text-xs font-medium">${doc.chunkCount || 0}</span></div>
                    <div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">文件大小</span><span class="text-gray-300 text-xs">${formatSize(doc.fileSize)}</span></div>
                    <div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">上传时间</span><span class="text-gray-300 text-xs">${formatTime(doc.uploadTime)}</span></div>
                    <div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">上传者</span><span class="text-gray-300 text-xs">${escapeHtml(doc.userId || '-')}</span></div>
                    ${doc.docCode ? `<div class="flex items-baseline gap-2"><span class="text-gray-600 text-xs w-14 shrink-0">文档编码</span><span class="text-gray-300 text-xs font-mono">${escapeHtml(doc.docCode)}</span></div>` : ''}
                    <div class="flex items-baseline gap-2 col-span-2"><span class="text-gray-600 text-xs w-14 shrink-0">源文件</span><span class="text-gray-400 text-xs truncate" title="${escapeHtml(doc.fileName || '-')}">${escapeHtml(doc.fileName || '-')}</span></div>
                    ${doc.errorMessage ? `<div class="flex items-baseline gap-2 col-span-2"><span class="text-gray-600 text-xs w-14 shrink-0">错误信息</span><span class="text-red-400 text-xs">${escapeHtml(doc.errorMessage)}</span></div>` : ''}
                </div>
            `;
            const desc = doc.description ? escapeHtml(doc.description) : '<span class="text-gray-600">（无描述信息）</span>';
            $('sourceModalContent').innerHTML = `<div class="text-xs text-gray-500 mb-2">文档描述</div><div class="text-sm text-gray-300 whitespace-pre-wrap leading-7">${desc}</div>`;
            $('sourceModal').classList.remove('hidden');
            $('sourceModal').classList.add('flex');
        }).catch(() => showToast('加载文档信息失败', 'error'));
}

/**
 * 删除文档（按 docCode 级联删除分片、知识点、关系、向量、文件）
 * 删除后记录到 localStorage，对话中引用该文档时标注"已删除"
 */
async function deleteDocument(docCode, fileName, docName, card) {
    if (!docCode) { showToast('该文档无 docCode，无法删除', 'error'); return; }
    if (!confirm(`确定要删除文档「${docName}」吗？\n\n将同时删除：所有分片、独占知识点、关联关系、向量数据和原始文件，此操作不可恢复。`)) return;

    // 进入删除中状态：禁用按钮、卡片半透明、显示 spinner
    if (card) {
        card.style.opacity = '0.5';
        card.style.pointerEvents = 'none';
        const btn = card.querySelector('.doc-delete-btn');
        if (btn) {
            btn.disabled = true;
            btn.classList.remove('w-9', 'h-9');
            btn.classList.add('w-auto', 'h-9', 'px-3');
            btn.innerHTML = '<span class="inline-block w-3.5 h-3.5 border-2 border-red-400 border-t-transparent rounded-full animate-spin"></span><span class="text-xs ml-1.5">删除中</span>';
        }
    }

    try {
        const res = await fetch(`${API_BASE}/docCode/${encodeURIComponent(docCode)}`, { method: 'DELETE' });
        if (res.ok) {
            if (card) card.remove();
            loadDocuments();
        } else {
            const err = await res.text();
            showToast('删除失败: ' + err, 'error');
            loadDocuments(); // 失败刷新卡片恢复状态
        }
    } catch (err) {
        showToast('删除失败: ' + err.message, 'error');
        loadDocuments(); // 失败刷新卡片恢复状态
    }
}

/* ---------- 知识图谱可视化 ---------- */
async function loadGraph() {
    const container = $('graphContainer');
    const empty = $('graphEmpty');
    const loading = $('graphLoading');
    const limit = $('graphLimit').value;

    empty.classList.add('hidden');
    container.classList.add('hidden');
    loading.classList.remove('hidden');
    $('nodeDetail').classList.add('hidden');

    try {
        const params = new URLSearchParams();
        params.append('limit', limit);
        const tenant = getTenant();
        const system = getSystem();
        if (tenant && tenant !== 'default') params.append('tenantCode', tenant);
        if (system && system !== 'default') params.append('systemType', system);

        const res = await fetch(`${API_BASE}/graph?${params.toString()}`);
        const data = await res.json();

        loading.classList.add('hidden');

        if (!data.nodes || data.nodes.length === 0) {
            empty.classList.remove('hidden');
            empty.querySelector('p')?.remove();
            empty.innerHTML = '<p class="text-gray-500 text-sm">暂无图谱数据，请先上传带 docCode 的文档以构建知识图谱</p>';
            $('graphStats').textContent = '';
            return;
        }

        container.classList.remove('hidden');
        renderGraph(data);
    } catch (err) {
        loading.classList.add('hidden');
        showToast('加载图谱失败: ' + err.message, 'error');
    }
}

function renderGraph(data) {
    const container = $('graphContainer');

    // 构建节点
    const nodes = new vis.DataSet(data.nodes.map(n => {
        const isChunk = n.group === 'chunk';
        return {
            id: n.id,
            label: n.label,
            title: n.title || n.label,
            group: n.group,
            shape: isChunk ? 'box' : 'dot',
            color: isChunk
                ? { background: 'rgba(96,165,250,0.2)', border: '#60a5fa', highlight: { background: 'rgba(96,165,250,0.4)', border: '#93c5fd' } }
                : { background: 'rgba(167,139,250,0.3)', border: '#a78bfa', highlight: { background: 'rgba(167,139,250,0.5)', border: '#c4b5fd' } },
            font: { color: '#e5e7eb', size: isChunk ? 11 : 13, face: 'sans-serif' },
            size: isChunk ? 16 : 18,
            borderWidth: 1.5,
            _raw: n
        };
    }));

    // 构建边
    const edges = new vis.DataSet(data.edges.map((e, i) => ({
        id: 'edge_' + i,
        from: e.from,
        to: e.to,
        label: e.label,
        color: { color: e.color || '#64748b', highlight: '#818cf8' },
        font: { color: '#94a3b8', size: 10, strokeWidth: 0 },
        arrows: { to: { enabled: true, scaleFactor: 0.5 } },
        smooth: { type: 'dynamic' },
        _raw: e
    })));

    const graphData = { nodes, edges };

    const options = {
        interaction: {
            hover: true,
            tooltipDelay: 100,
            zoomView: true,
            dragView: true,
            navigationButtons: true,
            keyboard: true
        },
        physics: {
            enabled: true,
            barnesHut: {
                gravitationalConstant: -3000,
                centralGravity: 0.3,
                springLength: 120,
                springConstant: 0.04,
                damping: 0.09
            },
            stabilization: { iterations: 150 }
        },
        groups: {
            chunk: { shape: 'box' },
            entity: { shape: 'dot' }
        }
    };

    if (state.graphNetwork) {
        state.graphNetwork.destroy();
    }
    state.graphNetwork = new vis.Network(container, graphData, options);

    // 稳定化完成后自动关闭物理模拟，防止节点多时一直乱动
    state.graphNetwork.on('stabilizationIterationsDone', () => {
        state.graphNetwork.setOptions({ physics: { enabled: false } });
    });

    // 点击节点显示详情
    state.graphNetwork.on('click', params => {
        if (params.nodes.length > 0) {
            const nodeId = params.nodes[0];
            const node = nodes.get(nodeId);
            showNodeDetail(node);
        } else {
            $('nodeDetail').classList.add('hidden');
        }
    });

    // 统计
    const chunkCount = data.nodes.filter(n => n.group === 'chunk').length;
    const entityCount = data.nodes.filter(n => n.group === 'entity').length;
    $('graphStats').textContent = `共 ${data.nodes.length} 个节点（分片 ${chunkCount} / 实体 ${entityCount}），${data.edges.length} 条关系`;
}

function showNodeDetail(node) {
    const detail = $('nodeDetail');
    const content = $('nodeDetailContent');
    detail.classList.remove('hidden');

    const raw = node._raw || {};
    const isChunk = raw.group === 'chunk';

    content.innerHTML = `
        <div class="grid grid-cols-2 gap-4">
            <div>
                <span class="text-xs text-gray-500">节点类型</span>
                <div class="mt-1"><span class="tag ${isChunk ? 'tag-blue' : 'tag-purple'}">${isChunk ? '文档分片 DocumentChunk' : '知识实体 KnowledgeEntity'}</span></div>
            </div>
            <div>
                <span class="text-xs text-gray-500">节点ID</span>
                <div class="mt-1 text-gray-300 text-xs font-mono">${escapeHtml(String(raw.id || ''))}</div>
            </div>
            ${isChunk ? `
                <div class="col-span-2">
                    <span class="text-xs text-gray-500">分片内容预览</span>
                    <div class="mt-1 p-3 bg-dark-850 rounded-lg text-xs text-gray-300 leading-relaxed max-h-32 overflow-y-auto">${escapeHtml(raw.title || raw.label || '')}</div>
                </div>
            ` : `
                <div>
                    <span class="text-xs text-gray-500">实体名称</span>
                    <div class="mt-1 text-gray-200 font-medium">${escapeHtml(raw.label || '')}</div>
                </div>
                <div>
                    <span class="text-xs text-gray-500">实体类型</span>
                    <div class="mt-1 text-gray-200">${escapeHtml(raw.type || '未知')}</div>
                </div>
            `}
        </div>
    `;
}

$('refreshGraph').addEventListener('click', loadGraph);
$('relayoutGraph').addEventListener('click', () => {
    if (state.graphNetwork) {
        state.graphNetwork.setOptions({ physics: { enabled: true } });
        state.graphNetwork.stabilize(200);
    }
});

/* ---------- 统计更新 ---------- */
async function updateStats() {
    try {
        const res = await fetch(`${API_BASE}/stats?tenantCode=${encodeURIComponent(getTenant())}`);
        const data = await res.json();
        $('statDocs').textContent = data.docCount ?? 0;
        $('statChunks').textContent = data.chunkCount ?? 0;
    } catch { /* 静默失败 */ }
}

/* ---------- 全局用户/租户/系统联动 ---------- */
$('globalUser').addEventListener('input', () => {
    syncLabels();
    state.sessionId = null;
    clearChat();
    loadHistory();
});
$('globalTenant').addEventListener('input', () => {
    syncLabels();
    $('uploadTenant').value = getTenant();
    $('urlTenant').value = getTenant();
    state.sessionId = null;
    clearChat();
    loadHistory();
    if (!$('tab-documents').classList.contains('hidden')) loadDocuments();
});
$('globalSystem').addEventListener('input', () => {
    syncLabels();
    $('uploadSystem').value = getSystem();
    $('urlSystem').value = getSystem();
    state.sessionId = null;
    clearChat();
    loadHistory();
    if (!$('tab-documents').classList.contains('hidden')) loadDocuments();
});

/* ---------- 初始化 ---------- */
function init() {
    loadCfg();
    initMarkdown();
    syncLabels();
    loadHistory();
    checkHealth();
    updateStats();
    setInterval(checkHealth, 30000);

    // 同步上传表单的租户/系统默认值
    $('uploadTenant').value = getTenant();
    $('uploadSystem').value = getSystem();
    $('urlTenant').value = getTenant();
    $('urlSystem').value = getSystem();

    // 配置变更自动保存
    ['globalUser', 'globalTenant', 'globalSystem'].forEach(id => {
        $(id).addEventListener('change', saveCfg);
    });

    // 聊天区向上滚动加载更早的对话
    $('chatMessages').addEventListener('scroll', () => {
        const el = $('chatMessages');
        if (el.scrollTop < 60) loadOlderMessages();
    });
}

init();
