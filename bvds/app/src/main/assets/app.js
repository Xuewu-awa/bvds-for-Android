// 日志存储（必须在所有 log() 调用之前初始化）
var _logs = [];
function log(msg, isErr) {
  var now = new Date();
  var ts = now.getHours().toString().padStart(2,'0') + ':' +
            now.getMinutes().toString().padStart(2,'0') + ':' +
            now.getSeconds().toString().padStart(2,'0') + '.' +
            now.getMilliseconds().toString().padStart(3,'0');
  _logs.push({ ts: ts, msg: String(msg), err: !!isErr });
  var line = document.createElement('div');
  line.className = 'log-line' + (isErr ? ' log-err' : '');
  line.innerHTML = '<span class="log-time">' + ts + '</span> ' + escapeHtml(String(msg));
  var body = document.getElementById('logBody');
  if (body) {
    body.appendChild(line);
    body.scrollTop = body.scrollHeight;
  }
  if (_logs.length > 500) { _logs.shift(); if (body && body.firstChild) body.removeChild(body.firstChild); }
}
log('[START] 脚本已加载');

// ============================================================
// 全局状态
// ============================================================
const QUALITY_MAP = {
  "1": { code: 120, name: "4K 超高清", needVip: true },
  "2": { code: 80,  name: "1080P 高清", needVip: false },
  "3": { code: 64,  name: "720P 准高清", needVip: false },
  "4": { code: 32,  name: "480P 清晰", needVip: false },
  "5": { code: 16,  name: "360P 流畅", needVip: false }
};

let state = {
  page: 'login',
  loggedIn: false,
  username: '',
  cookies: '',
  downloadDir: '',
  quality: '2',
  tasks: [],
  loginTimer: null
};

// ============================================================
// 日志系统（工具函数；log() 和 _logs 已在脚本顶部定义）
// ============================================================

function copyLog() {
  var text = _logs.map(function(l) { return l.ts + ' ' + l.msg; }).join('\n');

  try {
    Native.copyToClipboard(text);
    log('日志已复制 (' + text.length + ' 字符)');
  } catch(e) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed'; ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    log('日志已复制 (fallback, ' + text.length + ' 字符)');
  }
}

function exportLog() {
  var text = _logs.map(function(l) { return l.ts + ' ' + l.msg; }).join('\n');
  // 附带 Java 侧最后抓到的 HTML（截取前 200KB）
  try {
    var html = Native.getLastHtml();
    if (html && html.length > 0) {
      var maxLen = 200000;
      text += '\n\n========== HTML (' + html.length + ' bytes) ==========\n';
      text += html.substring(0, Math.min(html.length, maxLen));
      if (html.length > maxLen) text += '\n... [截断]';
    }
  } catch(e) {}

  var now = new Date();
  var ts = now.getFullYear() +
    ('0' + (now.getMonth()+1)).slice(-2) +
    ('0' + now.getDate()).slice(-2) + '_' +
    ('0' + now.getHours()).slice(-2) +
    ('0' + now.getMinutes()).slice(-2) +
    ('0' + now.getSeconds()).slice(-2);
  var filename = 'bvds_log_' + ts + '.txt';
  var dir = state.downloadDir;
  var fullPath = dir + '/' + filename;

  try {
    var ok = Native.saveTextFile(fullPath, text);
    if (ok) {
      log('日志已导出: ' + fullPath);
      toast('已导出: ' + filename);
    } else {
      log('导出失败', true);
      toast('导出失败');
    }
  } catch(e) {
    log('导出异常: ' + e.message, true);
    toast('导出失败');
  }
}

function clearLog() {
  _logs = [];
  var body = document.getElementById('logBody');
  if (body) body.innerHTML = '';
  log('日志已清空');
}

function openLog() {
  document.getElementById('logPanel').classList.add('show');
  document.getElementById('main').style.display = 'none';
  log('-- 日志面板已打开 --');
}

function closeLog() {
  document.getElementById('logPanel').classList.remove('show');
  document.getElementById('main').style.display = '';
}

// 长按 Logo 打开日志
(function initLogoLongPress() {
  var el = document.getElementById('logoBtn');
  if (!el) return;
  var timer = null;
  el.addEventListener('touchstart', function(e) {
    timer = setTimeout(function() {
      openLog();
    }, 800);
  });
  el.addEventListener('touchend', function(e) {
    if (timer) { clearTimeout(timer); timer = null; }
  });
  el.addEventListener('touchmove', function(e) {
    if (timer) { clearTimeout(timer); timer = null; }
  });
})();

// ============================================================
// 原生就绪回调
// ============================================================
function onNativeReady(downloadDir) {
  log('onNativeReady downloadDir=' + downloadDir);
  // 加载主题
  var theme = Native.getConfig('theme') || 'teal';
  applyTheme(theme);
  state.downloadDir = downloadDir;
  let q = Native.getConfig('quality');
  if (q && QUALITY_MAP[q]) state.quality = q;
  let dir = Native.getConfig('download_dir');
  if (dir) state.downloadDir = dir;
  log('配置加载完成 quality=' + state.quality + ' dir=' + state.downloadDir);

  // 直接进主页，后台检查登录态
  switchPage('home');
  log('后台检查登录...');
  Native.apiGet('https://api.bilibili.com/x/web-interface/nav', 'onCheckLogin');
}

function onCheckLogin(result) {
  try {
    var r = (typeof result === 'string') ? JSON.parse(result) : result;
    log('onCheckLogin status=' + r.status);
    if (r.status === 200) {
      let data = JSON.parse(r.body);
      if (data.code === 0 && data.data && data.data.isLogin) {
        state.loggedIn = true;
        state.username = data.data.uname || '用户';
        updateLoginUI();
        log('已登录: ' + state.username);
        return;
      }
    }
  } catch(e) {}
  state.loggedIn = false;
  updateLoginUI();
  log('未登录');
}

// ============================================================
// 登录流程
// ============================================================
function startLogin() {
  log('startLogin 开始获取二维码');
  var statusEl = document.getElementById('loginStatus');
  statusEl.textContent = '正在生成二维码...';
  statusEl.className = 'login-status waiting';

  Native.apiGet('https://passport.bilibili.com/x/passport-login/web/qrcode/generate', 'onQRGenerated');
}

function onQRGenerated(result) {
  try {
    var r = (typeof result === 'string') ? JSON.parse(result) : result;
    log('onQRGenerated status=' + r.status);
    if (r.status === 200) {
      let data = JSON.parse(r.body);
      log('onQRGenerated code=' + data.code);
      if (data.code === 0) {
        let url = data.data.url;
        let qrcodeKey = data.data.qrcode_key;

        // 生成二维码图片
        let base64 = Native.generateQRCode(url, 200);
        if (!base64 || base64.length < 100) {
          document.getElementById('loginStatus').textContent = '二维码生成失败';
          document.getElementById('loginStatus').className = 'login-status failed';
          log('onQRGenerated base64 为空或过短', true);
          return;
        }
        document.getElementById('qrImage').src = base64;

        document.getElementById('loginStatus').textContent = '请使用B站APP扫码';
        document.getElementById('loginStatus').className = 'login-status waiting';

        // 开始轮询
        pollLogin(qrcodeKey);
        return;
      }
    }
  } catch(e) {}
  document.getElementById('loginStatus').textContent = '获取二维码失败，请检查网络';
  document.getElementById('loginStatus').className = 'login-status failed';
}

function pollLogin(qrcodeKey) {
  if (state.loginTimer) clearInterval(state.loginTimer);

  var pollCount = 0;
  var maxPolls = 150; // 5 分钟超时 (150 × 2s)
  state.loginTimer = setInterval(function() {
    pollCount++;
    if (pollCount > maxPolls) {
      clearInterval(state.loginTimer);
      state.loginTimer = null;
      var statusEl = document.getElementById('loginStatus');
      if (statusEl) {
        statusEl.textContent = '二维码已超时，请重新获取';
        statusEl.className = 'login-status failed';
      }
      log('pollLogin 超时 (' + maxPolls + ' 次轮询)');
      return;
    }
    let url = 'https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=' + qrcodeKey;
    Native.apiGet(url, 'onPollResult');
  }, 2000);

  // 存储 key 用于回调判断
  window._pollKey = qrcodeKey;
}

function onPollResult(result) {
  try {
    var r = (typeof result === 'string') ? JSON.parse(result) : result;
    if (r.status === 200) {
      let data = JSON.parse(r.body);
      log('onPollResult code=' + data.code + ' inner=' + (data.data ? data.data.code : '?'));
      if (data.code === 0) {
        let innerCode = data.data.code;
        let statusEl = document.getElementById('loginStatus');

        if (innerCode === 0) {
          // 登录成功
          statusEl.textContent = '登录成功！';
          statusEl.className = 'login-status success';
          if (state.loginTimer) { clearInterval(state.loginTimer); state.loginTimer = null; }

          // 持久化 cookie
          let cookies = Native.getCookies();
          Native.setCookies(cookies);
          state.cookies = cookies;
          state.loggedIn = true;

          // 验证登录并跳转
          Native.apiGet('https://api.bilibili.com/x/web-interface/nav', 'onLoginConfirmed');
          // 同时直接跳转，不等验证结果
          setTimeout(function() {
            updateLoginUI();
            switchPage('home');
          }, 500);
        } else if (innerCode === 86038) {
          statusEl.textContent = '二维码已过期，重新生成中...';
          statusEl.className = 'login-status failed';
          if (state.loginTimer) { clearInterval(state.loginTimer); state.loginTimer = null; }
          setTimeout(startLogin, 1000);
        } else if (innerCode === 86090) {
          statusEl.textContent = '已扫描，请在手机上确认...';
          statusEl.className = 'login-status scanned';
        } else if (innerCode === 86101) {
          // 等待扫码
        }
      }
    }
  } catch(e) {}
}

function onLoginConfirmed(result) {
  try {
    let r = (typeof result === 'string') ? JSON.parse(result) : result;
    if (r.status === 200) {
      let data = JSON.parse(r.body);
      if (data.code === 0 && data.data && data.data.isLogin) {
        state.username = data.data.uname || '用户';
        state.loggedIn = true;
        Native.setCookies(Native.getCookies());
        updateLoginUI();
        toast('登录成功: ' + state.username);
        switchPage('home');
        return;
      }
    }
  } catch(e) {}
}

function skipLogin() {
  log('skipLogin 跳过登录');
  if (state.loginTimer) { clearInterval(state.loginTimer); state.loginTimer = null; }
  state.loggedIn = false;
  updateLoginUI();
  switchPage('home');
}

function logout() {
  Native.clearCookies();
  state.loggedIn = false;
  state.username = '';
  state.cookies = '';
  updateLoginUI();
  toast('已退出登录');
  switchPage('login');
  startLogin();
}

// ============================================================
// 页面导航
// ============================================================
function switchPage(name) {
  if (state.page === name) return;
  state.page = name;
  // 进入登录页自动拉二维码
  if (name === 'login' && !state.loggedIn) {
    setTimeout(startLogin, 200);
  }

  document.querySelectorAll('.page').forEach(function(p) { p.classList.remove('active'); });

  let pageId;
  switch(name) {
    case 'login': pageId = 'pageLogin'; break;
    case 'home': pageId = 'pageHome'; refreshHome(); break;
    case 'download': pageId = 'pageDownload'; refreshDownload(); break;
    case 'progress': pageId = 'pageProgress'; renderTasks(); break;
    case 'settings': pageId = 'pageSettings'; refreshSettings(); break;
    default: pageId = 'pageHome';
  }
  document.getElementById(pageId).classList.add('active');
}

function goDownload() { switchPage('download'); }
function goProgress() { switchPage('progress'); }
function goSettings() { switchPage('settings'); }

// 返回键
function onBackPressed() {
  if (state.page === 'download' || state.page === 'settings' || state.page === 'progress') {
    switchPage('home');
  } else if (state.page === 'login') {
    switchPage('home');
  }
}

// ============================================================
// 主页刷新
// ============================================================
function refreshHome() {
  document.getElementById('infoDir').textContent = state.downloadDir || '-';
  document.getElementById('infoQuality').textContent = QUALITY_MAP[state.quality] ? QUALITY_MAP[state.quality].name : '-';
  document.getElementById('infoLogin').textContent = state.loggedIn ? ('[OK] ' + state.username) : '[NG] 未登录';
  updateLoginUI();
}

function updateLoginUI() {
  let badge = document.getElementById('loginBadge');
  if (state.loggedIn) {
    badge.textContent = '[OK] ' + state.username;
    badge.className = 'login-badge on';
  } else {
    badge.textContent = '未登录';
    badge.className = 'login-badge off';
  }
}

// ============================================================
// 下载页
// ============================================================
function refreshDownload() {
  let savedQuality = Native.getConfig('quality');
  if (savedQuality && QUALITY_MAP[savedQuality]) {
    state.quality = savedQuality;
  }
  document.getElementById('qualitySelect').value = state.quality;

  // URL 输入监听（防重复绑定）
  if (!window._parseUrlsBound) {
    window._parseUrlsBound = true;
    document.getElementById('inputUrls').addEventListener('input', parseUrls);
  }
}

// 实时预览输入的 URL（委托 Java 解析）
function parseUrls() {
  let text = document.getElementById('inputUrls').value.trim();
  if (!text) {
    document.getElementById('urlCount').textContent = '0';
    document.getElementById('urlList').innerHTML = '';
    return;
  }
  var urls = [];
  try {
    var json = Native.extractUrls(text);
    urls = JSON.parse(json);
  } catch(e) { urls = []; }
  document.getElementById('urlCount').textContent = urls.length;
  var list = document.getElementById('urlList');
  if (urls.length === 0) {
    list.innerHTML = '';
  } else {
    list.innerHTML = urls.map(function(u, i) {
      return '<div class="url-item">' + (i+1) + '. ' + u + '</div>';
    }).join('');
  }
}

// 初始化模式选择器
document.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('#modeSelect button').forEach(function(btn) {
    btn.onclick = function() {
      document.querySelectorAll('#modeSelect button').forEach(function(b) { b.classList.remove('active'); });
      btn.classList.add('active');
    };
  });
  document.getElementById('inputUrls').addEventListener('input', parseUrls);
});

// ============================================================
// 开始下载（UI 逻辑：解析输入 → 创建任务 → 委托 Java）
// ============================================================
function startDownload() {
  var text = document.getElementById('inputUrls').value.trim();
  log('startDownload text=' + text.substring(0, 50) + '...');
  if (!text) { toast('请输入视频链接'); return; }

  // Java 侧解析 URL 列表
  var urls = [];
  try {
    var urlsJson = Native.extractUrls(text);
    urls = JSON.parse(urlsJson);
  } catch(e) {
    log('extractUrls 失败: ' + e.message, true);
  }

  if (urls.length === 0) { toast('未识别到有效B站链接'); return; }

  var modeBtn = document.querySelector('#modeSelect button.active');
  var mode = modeBtn ? modeBtn.getAttribute('data-mode') : '3';
  let quality = document.getElementById('qualitySelect').value;

  Native.setConfig('quality', quality);
  state.quality = quality;

  let qualityName = QUALITY_MAP[quality] ? QUALITY_MAP[quality].name : '1080P';

  urls.forEach(function(url) {
    let taskId = 'task_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);
    state.tasks.push({
      id: taskId,
      url: url,
      mode: mode,
      quality: quality,
      qualityName: qualityName,
      title: '识别中...',
      status: 'pending',
      progress: 0
    });
  });

  toast('已添加 ' + urls.length + ' 个下载任务');
  switchPage('progress');
  processNextTask();
}

// ============================================================
// 任务调度（纯 UI：委托 Java fetchVideoInfo / downloadTask）
// ============================================================
function processNextTask() {
  var pending = state.tasks.filter(function(t) { return t.status === 'pending'; });
  if (pending.length === 0) return;

  var running = state.tasks.filter(function(t) { return t.status === 'downloading' || t.status === 'merging'; });
  if (running.length >= 3) return;

  var task = pending[0];
  task.status = 'downloading';
  renderTasks();

  log('processNextTask id=' + task.id + ' url=' + task.url + ' quality=' + task.quality);
  try {
    Native.fetchVideoInfo(task.url, task.quality, task.id);
  } catch(e) {
    log('fetchVideoInfo 调用失败: ' + e.message, true);
    failTask(task, '调用失败');
  }
}

// Java 回调：视频信息解析结果
function onVideoInfoResult(taskId, result) {
  var task = findTask(taskId);
  if (!task) { log('onVideoInfoResult 找不到任务 ' + taskId, true); return; }
  try {
    var info = (typeof result === 'string') ? JSON.parse(result) : result;
    log('onVideoInfoResult id=' + taskId + ' title=' + (info.title||'?') + ' videoUrl=' + (info.videoUrl ? 'OK' : 'null') + ' audioUrl=' + (info.audioUrl ? 'OK' : 'null') + ' error=' + (info.error||''));
    if (info.error) {
      failTask(task, info.error);
      return;
    }
    task.title = info.title || '未知标题';
    task.sourceId = info.sourceId || '';
    task.videoUrl = info.videoUrl;
    task.audioUrl = info.audioUrl;

    if (!task.videoUrl) {
      failTask(task, '无法获取视频链接');
      return;
    }

    // 委托 Java 执行下载
    var dir = state.downloadDir;
    var safeTitle = cleanFilename(task.title);
    var ext = (task.mode === '2') ? '.mp3' : '.mp4';
    task.destPath = dir + '/' + safeTitle + ext;
    task.status = 'downloading';
    renderTasks();

    Native.downloadTask(task.id, task.videoUrl, task.audioUrl || '', task.mode, task.destPath);
  } catch(e) {
    failTask(task, '解析结果异常');
  }
}

// Java 回调：下载进度
function onTaskProgress(taskId, percent, status) {
  log('onTaskProgress id=' + taskId + ' pct=' + percent + ' status=' + status);
  var task = findTask(taskId);
  if (!task) return;
  task.progress = percent;
  task.status = (status === 'merging') ? 'merging' : 'downloading';
  renderTasks();
}

// Java 回调：下载完成
function onTaskComplete(taskId, success, path) {
  log('onTaskComplete id=' + taskId + ' ok=' + success + ' path=' + path);
  var task = findTask(taskId);
  if (!task) return;

  if (success) {
    task.status = 'done';
    task.progress = 100;
    task.destPath = path;
    toast('[OK] ' + task.title);
  } else {
    failTask(task, path || '下载失败');
  }
  renderTasks();
  processNextTask();
}

// Java 回调：下载失败
function onTaskFailed(taskId, error) {
  log('onTaskFailed id=' + taskId + ' error=' + error, true);
  var task = findTask(taskId);
  if (!task) return;
  failTask(task, error);
  processNextTask();
}

function failTask(task, error) {
  task.status = 'failed';
  task.error = error;
  renderTasks();
}

function findTask(taskId) {
  return state.tasks.find(function(t) { return t.id === taskId; });
}

function clearTasks() {
  state.tasks = state.tasks.filter(function(t) { return t.status !== 'done' && t.status !== 'failed'; });
  renderTasks();
}

function retryTask(taskId) {
  var task = findTask(taskId);
  if (!task) return;
  log('retryTask id=' + taskId + ' url=' + task.url);
  task.status = 'pending';
  task.progress = 0;
  task.error = '';
  renderTasks();
  processNextTask();
}

// ============================================================
// 进度页渲染
// ============================================================
function renderTasks() {
  let list = document.getElementById('taskList');
  let stats = document.getElementById('taskStats');

  let done = state.tasks.filter(function(t) { return t.status === 'done'; }).length;
  let failed = state.tasks.filter(function(t) { return t.status === 'failed'; }).length;
  let total = state.tasks.length;
  stats.textContent = done + ' / ' + total + (failed > 0 ? ' (' + failed + ' 失败)' : '');

  if (total === 0) {
    list.innerHTML = '<div class="empty"><div class="icon">—</div><div class="label">暂无下载任务</div></div>';
    return;
  }

  list.innerHTML = state.tasks.map(function(t) {
    let statusClass = t.status;
    let statusText = {
      'pending': '等待中',
      'downloading': '下载中 ' + t.progress + '%',
      'merging': '合并中...',
      'done': '[OK] 完成',
      'failed': '[NG] 失败'
    }[t.status] || t.status;

    return '<div class="task-item">' +
      '<div class="task-header">' +
        '<span class="task-name">' + escapeHtml(t.title) + '</span>' +
        '<span class="task-status ' + statusClass + '">' + statusText + '</span>' +
      '</div>' +
      (t.status === 'downloading' || t.status === 'merging' ?
        '<div class="task-progress"><div class="bar" style="width:' + t.progress + '%"></div></div>' : '') +
      '<div class="task-meta">' +
        t.qualityName + (t.error ? ' · ' + t.error : '') +
      '</div>' +
      (t.status === 'failed' ?
        '<div style="margin-top:0.5rem;display:flex;gap:0.5rem;">' +
          '<button class="btn btn-sm btn-retry" onclick="retryTask(\'' + t.id + '\')">重试</button>' +
        '</div>' : '') +
    '</div>';
  }).join('');
}

// ============================================================
// 设置页
// ============================================================
function applyTheme(name) {
  document.body.className = name === 'amber' ? 'theme-amber' : '';
  Native.setConfig('theme', name);
}

function refreshSettings() {
  var theme = Native.getConfig('theme') || 'teal';
  document.getElementById('themeSelect').value = theme;
  applyTheme(theme);
  document.getElementById('inputDir').value = state.downloadDir;
  document.getElementById('defaultQualitySelect').value = state.quality;

  document.getElementById('inputDir').onchange = function() {
    let dir = this.value.trim();
    if (dir) {
      Native.setDownloadDir(dir);
      state.downloadDir = dir;
      Native.setConfig('download_dir', dir);
      toast('下载目录已更新');
    }
  };

  document.getElementById('defaultQualitySelect').onchange = function() {
    state.quality = this.value;
    Native.setConfig('quality', this.value);
    toast('默认清晰度: ' + QUALITY_MAP[this.value].name);
  };
}

function resetDownloadDir() {
  let dir = Native.getDefaultDownloadDir();
  state.downloadDir = dir;
  Native.setDownloadDir(dir);
  Native.setConfig('download_dir', dir);
  document.getElementById('inputDir').value = dir;
  toast('已恢复默认目录');
}

// ============================================================
// 工具函数
// ============================================================
function cleanFilename(name) {
  if (!name) return 'untitled';

  // 提取《》中的内容
  let m = name.match(/《([^《》]+)》/);
  if (m) name = m[1];

  // 移除非法字符（含 Unicode 引号、智能引号）
  name = name.replace(/[<>:"\u201C\u201D\u2018\u2019\u0022\/\\|?*]/g, '');

  if (name.length > 100) name = name.substring(0, 100);
  return name.trim() || 'untitled';
}

function escapeHtml(s) {
  if (!s) return '';
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function toast(msg) {
  let el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.add('show');
  setTimeout(function() { el.classList.remove('show'); }, 2000);
}

// ============================================================
// 初始化
// ============================================================
document.addEventListener('DOMContentLoaded', function() {
  document.getElementById('inputUrls').addEventListener('input', parseUrls);

  // 轮询处理新任务
  setInterval(function() {
    let pending = state.tasks.filter(function(t) { return t.status === 'pending'; });
    if (pending.length > 0) {
      let running = state.tasks.filter(function(t) { return t.status === 'downloading' || t.status === 'merging'; });
      if (running.length < 3) processNextTask();
    }
  }, 1000);
});
