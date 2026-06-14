// FastDrop Share client javascript
const State = {
  serverIp: '',
  port: '8080',
  currentPath: '',
  files: [],
  filter: 'all',
  searchQuery: '',
  uploadQueue: [],
  isUploading: false,
  activeXhrs: {} // Track active XHR uploads to support cancelling
};

// DOM Elements cache
const DOM = {
  statusIndicator: document.getElementById('status-indicator'),
  statusText: document.getElementById('status-text'),
  ipInput: document.getElementById('ip-input'),
  connectBtn: document.getElementById('connect-btn'),
  connectionPanel: document.getElementById('connection-panel'),
  sharingPanel: document.getElementById('sharing-panel'),
  dropzone: document.getElementById('dropzone'),
  fileSelector: document.getElementById('file-selector'),
  uploadQueueSection: document.getElementById('upload-queue-section'),
  uploadQueueList: document.getElementById('upload-queue-list'),
  cancelAllBtn: document.getElementById('cancel-all-btn'),
  pathDisplay: document.getElementById('path-display'),
  upDirBtn: document.getElementById('up-dir-btn'),
  searchInput: document.getElementById('search-input'),
  filesList: document.getElementById('files-list'),
  emptyState: document.getElementById('empty-state'),
  toast: document.getElementById('toast'),
  toastText: document.getElementById('toast-text'),
  filesTabBtn: document.getElementById('files-tab-btn')
};

// INITIALIZATION
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

function initApp() {
  // Setup event listeners
  DOM.connectBtn.addEventListener('click', connectToServer);
  DOM.dropzone.addEventListener('click', () => DOM.fileSelector.click());
  DOM.fileSelector.addEventListener('change', handleFileSelectorChange);
  DOM.cancelAllBtn.addEventListener('click', cancelAllUploads);
  DOM.upDirBtn.addEventListener('click', navigateUp);
  DOM.searchInput.addEventListener('input', handleSearchInput);

  // Setup tabs
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const targetTab = btn.getAttribute('data-tab');
      switchTab(targetTab);
    });
  });

  // Setup filters
  document.querySelectorAll('.filter-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.filter-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      State.filter = chip.getAttribute('data-filter');
      renderFiles();
    });
  });

  // Setup Drag & Drop
  setupDragAndDrop();

  // Setup Clipboard Paste Listener
  setupClipboardPaste();

  // Parse URL Parameters for auto-connection
  const urlParams = new URLSearchParams(window.location.search);
  const paramIp = urlParams.get('ip') || window.location.hostname;
  
  if (paramIp && paramIp !== 'localhost' && paramIp !== '127.0.0.1' && paramIp !== '') {
    DOM.ipInput.value = paramIp;
    State.serverIp = paramIp;
    connectToServer();
  } else {
    // Check localStorage
    const savedIp = localStorage.getItem('fastdrop_share_ip');
    if (savedIp) {
      DOM.ipInput.value = savedIp;
      State.serverIp = savedIp;
    }
  }
}

// NAVIGATION TABS
function switchTab(tabId) {
  document.querySelectorAll('.tab-btn').forEach(btn => {
    if (btn.getAttribute('data-tab') === tabId) {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });

  document.querySelectorAll('.tab-content').forEach(content => {
    if (content.getAttribute('id') === tabId) {
      content.classList.add('active');
    } else {
      content.classList.remove('active');
    }
  });

  if (tabId === 'files-tab') {
    loadFiles();
  }
}

// BREADCRUMBS
function updateBreadcrumbs() {
  DOM.pathDisplay.innerText = State.currentPath ? `Shared Folder / ${State.currentPath}` : 'Shared Folder /';
  if (State.currentPath) {
    DOM.upDirBtn.classList.remove('hidden');
  } else {
    DOM.upDirBtn.classList.add('hidden');
  }
}

// BASE URL RESOLUTION
function getBaseUrl() {
  if (!State.serverIp) {
    return window.location.origin;
  }
  return `http://${State.serverIp}:${State.port}`;
}

// CONNECT TO SERVER
function connectToServer() {
  const ip = DOM.ipInput.value.trim();
  if (!ip) {
    showToast("Please enter a valid IP address");
    return;
  }

  State.serverIp = ip;
  DOM.statusText.innerText = "Connecting...";
  
  // Verify server by loading files list
  const url = `${getBaseUrl()}/api/files?path=`;
  fetch(url)
    .then(res => {
      if (res.ok) return res.json();
      throw new Error(`Server returned HTTP ${res.status}`);
    })
    .then(data => {
      // Successful connection!
      localStorage.setItem('fastdrop_share_ip', ip);
      DOM.statusIndicator.classList.add('connected');
      DOM.statusText.innerText = `Connected to ${ip}`;
      DOM.connectionPanel.classList.add('hidden');
      DOM.sharingPanel.classList.remove('hidden');
      showToast("Successfully Connected to Server!", 2000);
      
      // Auto-load files in background
      State.files = data;
    })
    .catch(err => {
      console.error(err);
      DOM.statusIndicator.classList.remove('connected');
      DOM.statusText.innerText = "Disconnected";
      showToast("Failed to connect. Check IP and Wi-Fi connection.");
    });
}

// UPLOAD PROCESSOR (HIGH SPEED PIPED BINARY STREAM)
function handleFileSelectorChange(e) {
  const files = e.target.files;
  if (files && files.length > 0) {
    addFilesToQueue(files);
  }
}

function addFilesToQueue(fileList) {
  const files = Array.from(fileList);
  files.forEach(file => {
    // Unique ID for each upload row in the queue UI
    const uploadId = 'up_' + Math.random().toString(36).substring(2, 9);
    const item = {
      id: uploadId,
      file: file,
      status: 'pending',
      progress: 0,
      speed: '0.00 KB/s',
      eta: 'Waiting...'
    };
    State.uploadQueue.push(item);
    renderQueueRow(item);
  });

  DOM.uploadQueueSection.classList.remove('hidden');
  
  if (!State.isUploading) {
    processNextUpload();
  }
}

function renderQueueRow(item) {
  const row = document.createElement('div');
  row.className = 'queue-item';
  row.id = item.id;
  
  const formattedSize = formatBytes(item.file.size);
  row.innerHTML = `
    <div class="queue-item-header">
      <span class="queue-item-name" title="${item.file.name}">${item.file.name}</span>
      <span class="queue-item-meta" id="meta-${item.id}">${formattedSize}</span>
      <button class="btn-cancel-upload" onclick="cancelUpload('${item.id}')" title="Cancel Upload">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 16px; height: 16px;"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div class="queue-progress-row">
      <div class="progress-bar-bg">
        <div class="progress-bar-fill" id="fill-${item.id}" style="width: 0%;"></div>
      </div>
    </div>
    <div class="queue-item-stats">
      <span class="queue-item-speed" id="speed-${item.id}">0.00 KB/s</span>
      <span class="queue-item-eta" id="eta-${item.id}">ETA: Calculating...</span>
    </div>
  `;
  
  DOM.uploadQueueList.appendChild(row);
}

function processNextUpload() {
  const next = State.uploadQueue.find(item => item.status === 'pending');
  if (!next) {
    State.isUploading = false;
    showToast("All uploads completed successfully!", 3000);
    // Reload files list if active
    if (document.getElementById('files-tab').classList.contains('active')) {
      loadFiles();
    }
    return;
  }

  State.isUploading = true;
  next.status = 'uploading';
  
  const file = next.file;
  const startTime = Date.now();
  const url = `${getBaseUrl()}/api/upload?name=${encodeURIComponent(file.name)}&path=${encodeURIComponent(State.currentPath)}`;
  
  const xhr = new XMLHttpRequest();
  State.activeXhrs[next.id] = xhr;

  xhr.open('POST', url, true);
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');

  xhr.upload.addEventListener('progress', (e) => {
    if (e.lengthComputable) {
      const percent = Math.floor((e.loaded / e.total) * 100);
      next.progress = percent;
      
      const fill = document.getElementById(`fill-${next.id}`);
      const meta = document.getElementById(`meta-${next.id}`);
      if (fill) fill.style.width = `${percent}%`;
      if (meta) meta.innerText = `${formatBytes(e.loaded)} / ${formatBytes(e.total)}`;

      // Speed calculation
      const elapsedSeconds = (Date.now() - startTime) / 1000;
      const speed = elapsedSeconds > 0 ? (e.loaded / elapsedSeconds) : 0;
      let speedText = '0.00 KB/s';
      if (speed > 1024 * 1024) {
        speedText = `${(speed / (1024 * 1024)).toFixed(2)} MB/s`;
      } else if (speed > 1024) {
        speedText = `${(speed / 1024).toFixed(2)} KB/s`;
      } else {
        speedText = `${speed.toFixed(0)} B/s`;
      }
      
      const speedEl = document.getElementById(`speed-${next.id}`);
      if (speedEl) speedEl.innerText = speedText;

      // ETA calculation
      const remainingBytes = e.total - e.loaded;
      const etaSeconds = speed > 0 ? Math.ceil(remainingBytes / speed) : 0;
      let etaText = 'ETA: --';
      if (etaSeconds > 3600) {
        etaText = `ETA: ${Math.floor(etaSeconds / 3600)}h ${Math.floor((etaSeconds % 3600) / 60)}m`;
      } else if (etaSeconds > 60) {
        etaText = `ETA: ${Math.floor(etaSeconds / 60)}m ${etaSeconds % 60}s`;
      } else {
        etaText = `ETA: ${etaSeconds}s`;
      }
      
      const etaEl = document.getElementById(`eta-${next.id}`);
      if (etaEl) etaEl.innerText = etaText;
    }
  });

  xhr.addEventListener('load', () => {
    delete State.activeXhrs[next.id];
    if (xhr.status === 200) {
      next.status = 'completed';
      const row = document.getElementById(next.id);
      if (row) {
        row.style.opacity = '0.6';
        setTimeout(() => row.remove(), 2000);
      }
      processNextUpload();
    } else {
      next.status = 'failed';
      const row = document.getElementById(next.id);
      if (row) {
        row.querySelector('.queue-item-stats').innerHTML = `<span style="color:#ef4444; font-weight:700;">Upload Failed (HTTP ${xhr.status})</span>`;
      }
      processNextUpload();
    }
  });

  xhr.addEventListener('error', () => {
    delete State.activeXhrs[next.id];
    next.status = 'failed';
    const row = document.getElementById(next.id);
    if (row) {
      row.querySelector('.queue-item-stats').innerHTML = `<span style="color:#ef4444; font-weight:700;">Network Error</span>`;
    }
    processNextUpload();
  });

  xhr.send(file);
}

// CANCEL INDIVIDUAL UPLOAD
window.cancelUpload = function(uploadId) {
  const index = State.uploadQueue.findIndex(item => item.id === uploadId);
  if (index !== -1) {
    const item = State.uploadQueue[index];
    if (State.activeXhrs[uploadId]) {
      State.activeXhrs[uploadId].abort();
      delete State.activeXhrs[uploadId];
    }
    
    State.uploadQueue.splice(index, 1);
    const row = document.getElementById(uploadId);
    if (row) row.remove();
    
    if (item.status === 'uploading') {
      processNextUpload();
    }
  }
  
  if (State.uploadQueue.length === 0) {
    DOM.uploadQueueSection.classList.add('hidden');
  }
};

// CANCEL ALL UPLOADS
function cancelAllUploads() {
  Object.keys(State.activeXhrs).forEach(id => {
    State.activeXhrs[id].abort();
    delete State.activeXhrs[id];
  });
  
  State.uploadQueue = [];
  State.isUploading = false;
  DOM.uploadQueueList.innerHTML = '';
  DOM.uploadQueueSection.classList.add('hidden');
  showToast("All uploads cancelled");
}

// DRAG AND DROP HANDLERS
function setupDragAndDrop() {
  const dropzone = DOM.dropzone;
  
  ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
    window.addEventListener(eventName, preventDefaults, false);
    document.body.addEventListener(eventName, preventDefaults, false);
    dropzone.addEventListener(eventName, preventDefaults, false);
  });

  function preventDefaults(e) {
    e.preventDefault();
    e.stopPropagation();
  }

  ['dragenter', 'dragover'].forEach(eventName => {
    window.addEventListener(eventName, () => {
      dropzone.classList.add('highlight');
    }, false);
  });

  ['dragleave', 'drop'].forEach(eventName => {
    window.addEventListener(eventName, () => {
      dropzone.classList.remove('highlight');
    }, false);
  });

  dropzone.addEventListener('drop', (e) => {
    const dt = e.dataTransfer;
    const files = dt.files;
    if (files && files.length > 0) {
      addFilesToQueue(files);
    }
  });
}

// CLIPBOARD COPY-PASTE UPLOAD SUPPORT
function setupClipboardPaste() {
  window.addEventListener('paste', (e) => {
    // Check if connected first
    if (DOM.sharingPanel.classList.contains('hidden')) return;

    const clipboardItems = (e.clipboardData || window.clipboardData).items;
    const files = [];
    for (const item of clipboardItems) {
      if (item.kind === 'file') {
        const file = item.getAsFile();
        if (file) files.push(file);
      }
    }
    if (files.length > 0) {
      showToast(`Pasting ${files.length} file(s)...`, 1500);
      addFilesToQueue(files);
    }
  });
}

// LOAD FILES FROM PC
function loadFiles(subpath = null) {
  if (subpath !== null) {
    State.currentPath = subpath;
  }
  
  updateBreadcrumbs();
  
  const url = `${getBaseUrl()}/api/files?path=${encodeURIComponent(State.currentPath)}`;
  fetch(url)
    .then(res => {
      if (res.ok) return res.json();
      throw new Error(`HTTP ${res.status}`);
    })
    .then(data => {
      State.files = data;
      renderFiles();
    })
    .catch(err => {
      console.error(err);
      showToast("Error loading folder contents.");
    });
}

function handleSearchInput(e) {
  State.searchQuery = e.target.value.toLowerCase().trim();
  renderFiles();
}

function navigateUp() {
  if (!State.currentPath) return;
  const parts = State.currentPath.split('/');
  parts.pop(); // remove last dir
  loadFiles(parts.join('/'));
}

// RENDER PC SHARED FILES
function renderFiles() {
  DOM.filesList.innerHTML = '';
  
  const filtered = State.files.filter(item => {
    // Text search query filter
    const matchesSearch = item.name.toLowerCase().includes(State.searchQuery);
    
    // Category filter
    let matchesCategory = true;
    if (State.filter !== 'all') {
      matchesCategory = item.type === State.filter;
    }
    
    return matchesSearch && (matchesCategory || item.type === 'folder');
  });

  if (filtered.length === 0) {
    DOM.emptyState.classList.remove('hidden');
    return;
  }
  
  DOM.emptyState.classList.add('hidden');

  filtered.forEach(item => {
    const row = document.createElement('div');
    row.className = 'file-row';
    row.setAttribute('data-type', item.type);

    let iconHtml = '';
    switch(item.type) {
      case 'folder':
        iconHtml = '<svg viewBox="0 0 24 24" fill="currentColor" style="width: 24px; height: 24px;"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>';
        break;
      case 'video':
        iconHtml = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 24px; height: 24px;"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>';
        break;
      case 'audio':
        iconHtml = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 24px; height: 24px;"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>';
        break;
      case 'image':
        iconHtml = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 24px; height: 24px;"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>';
        break;
      default:
        iconHtml = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 24px; height: 24px;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
        break;
    }

    const nameText = item.name;
    const metaText = item.type === 'folder' ? 'Folder' : `${item.sizeFormatted}`;

    let actionButtons = '';
    if (item.type !== 'folder') {
      const castButton = item.type === 'video' ? `
        <button class="action-btn btn-cast" onclick="castVideo('${item.relativePath}')" title="Cast Movie to TV">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width:16px; height:16px;"><path d="M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"/><path d="M12 17h.01"/><path d="M8 21h8"/><path d="M12 17v4"/></svg>
        </button>
      ` : '';
      
      actionButtons = `
        <div class="file-actions">
          ${castButton}
          <button class="action-btn" onclick="downloadFile('${item.relativePath}', '${item.name}')" title="Download File">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 16px; height: 16px;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <button class="action-btn" onclick="streamFile('${item.relativePath}')" title="Stream/View File">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 16px; height: 16px;"><polygon points="5 3 19 12 5 21 5 3"/></svg>
          </button>
          <button class="action-btn btn-delete" onclick="deleteFile('${item.relativePath}', '${item.name}')" title="Delete File">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 16px; height: 16px;"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
          </button>
        </div>
      `;
    } else {
      actionButtons = `
        <div class="file-actions">
          <button class="action-btn btn-delete" onclick="deleteFile('${item.relativePath}', '${item.name}')" title="Delete Folder">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="width: 16px; height: 16px;"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
          </button>
        </div>
      `;
    }

    row.innerHTML = `
      <div class="file-icon">${iconHtml}</div>
      <div class="file-info" onclick="handleFileRowClick('${item.type}', '${item.relativePath}')">
        <span class="file-name" title="${nameText}">${nameText}</span>
        <span class="file-meta">${metaText}</span>
      </div>
      ${actionButtons}
    `;

    DOM.filesList.appendChild(row);
  });
}

window.handleFileRowClick = function(type, relativePath) {
  if (type === 'folder') {
    loadFiles(relativePath);
  }
};

// ACTIONS IN FILE LIST
window.downloadFile = function(relativePath, name) {
  const url = `${getBaseUrl()}/stream?path=${encodeURIComponent(relativePath)}&download=true`;
  showToast(`Downloading: ${name}...`, 2000);
  
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
};

window.streamFile = function(relativePath) {
  const url = `${getBaseUrl()}/stream?path=${encodeURIComponent(relativePath)}`;
  window.open(url, '_blank');
};

window.castVideo = function(relativePath) {
  const url = `${getBaseUrl()}/api/cast/play?path=${encodeURIComponent(relativePath)}`;
  fetch(url)
    .then(res => {
      if (res.ok) {
        showToast("Casting video successfully triggered on TV receiver!", 2500);
      } else {
        showToast("Failed to trigger casting");
      }
    })
    .catch(err => {
      console.error(err);
      showToast("Casting error. Verify connection address.");
    });
};

window.deleteFile = function(relativePath, name) {
  if (!confirm(`Are you sure you want to delete "${name}"?`)) return;

  const url = `${getBaseUrl()}/api/delete?path=${encodeURIComponent(relativePath)}`;
  fetch(url, { method: 'DELETE' })
    .then(res => {
      if (res.ok) return res.json();
      throw new Error(`HTTP ${res.status}`);
    })
    .then(data => {
      showToast(`Deleted "${name}" successfully`, 2000);
      loadFiles(); // reload listing
    })
    .catch(err => {
      console.error(err);
      showToast(`Delete failed for ${name}`);
    });
};

// TOAST NOTIFICATIONS
function showToast(message, duration = 2000) {
  DOM.toastText.innerText = message;
  DOM.toast.classList.remove('hidden');
  
  // Clear any existing timeouts if possible
  if (window.toastTimeout) {
    clearTimeout(window.toastTimeout);
  }
  
  window.toastTimeout = setTimeout(() => {
    DOM.toast.classList.add('hidden');
  }, duration);
}

// BYTES TO HUMAN READABLE FORMAT
function formatBytes(bytes) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}
