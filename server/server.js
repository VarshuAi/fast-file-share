const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const os = require('os');

// Initialize Express app
const app = express();

// Enable CORS so the Tizen TV app (running on a different IP/origin) can query this server
app.use(cors());

// Global log utility
function log(msg) {
  const timestamp = new Date().toISOString().replace('T', ' ').substring(0, 19);
  console.log(`[${timestamp}] ${msg}`);
}

// Fallback MIME-Type Dictionary in case the npm 'mime-types' package isn't installed
const MIME_TYPES_FALLBACK = {
  // Video
  '.mp4': 'video/mp4',
  '.mkv': 'video/x-matroska',
  '.webm': 'video/webm',
  '.avi': 'video/x-msvideo',
  // Audio
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.m4a': 'audio/mp4',
  // Image
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.webp': 'image/webp',
  // Subtitles
  '.vtt': 'text/vtt',
  '.srt': 'text/vtt',
  // PDF
  '.pdf': 'application/pdf'
};

function getMimeType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  try {
    const mime = require('mime-types');
    return mime.lookup(filePath) || MIME_TYPES_FALLBACK[ext] || 'application/octet-stream';
  } catch (e) {
    return MIME_TYPES_FALLBACK[ext] || 'application/octet-stream';
  }
}

// Load configurations from config.json
let config = { sharedFolder: '', port: 8080 };
const configPath = path.join(__dirname, 'config.json');

try {
  if (fs.existsSync(configPath)) {
    config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  }
} catch (error) {
  log(`Warning: Failed to load config.json (${error.message}). Using defaults.`);
}

// Set defaults if missing
if (!config.port) config.port = 8080;
if (!config.sharedFolder) {
  config.sharedFolder = path.join(__dirname, 'shared_media');
}

// Resolve absolute path of the shared folder
let resolvedSharedFolder = path.resolve(config.sharedFolder);

// Verify if the configured folder exists, if not create a fallback demo folder
if (!fs.existsSync(resolvedSharedFolder)) {
  log(`Warning: Configured shared folder "${resolvedSharedFolder}" does not exist.`);
  resolvedSharedFolder = path.join(__dirname, 'shared_media');
  log(`Creating fallback shared folder at: ${resolvedSharedFolder}`);
  fs.mkdirSync(resolvedSharedFolder, { recursive: true });
  
  // Create sample files for immediate testing if folder is empty
  const sampleFilePath = path.join(resolvedSharedFolder, 'Welcome to FastDrop.txt');
  if (!fs.existsSync(sampleFilePath)) {
    fs.writeFileSync(sampleFilePath, 'Welcome to FastDrop TV! Place your media files (videos, images, music) in this folder to stream them to your Samsung Tizen TV.');
  }
}

log(`Active Shared Directory: ${resolvedSharedFolder}`);

// Helper to check for path traversal vulnerability
function isPathSafe(targetPath) {
  const resolvedTarget = path.resolve(targetPath);
  return resolvedTarget.startsWith(resolvedSharedFolder);
}

// Helper to format file sizes into human-readable strings (MB / GB)
function formatBytes(bytes) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Get Local Network IP addresses (IPv4, non-internal)
function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const interfaceName in interfaces) {
    for (const iface of interfaces[interfaceName]) {
      // Filter out IPv6 and internal loopback addresses
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push(iface.address);
      }
    }
  }
  return addresses;
}

// Request Logger Middleware
app.use((req, res, next) => {
  log(`${req.method} ${req.url} [IP: ${req.ip}]`);
  next();
});

// Serve the Tizen TV Client Web App static files on /client
app.use('/client', express.static(path.join(__dirname, '../tizen-tv-app')));

// Endpoint: GET /
// Returns server dashboard info
app.get('/', (req, res) => {
  const ips = getLocalIPs();
  const ipList = ips.map(ip => `<li><strong>${ip}</strong></li>`).join('');
  const responseHtml = `
    <!DOCTYPE html>
    <html>
    <head>
      <title>FastDrop TV Server Dashboard</title>
      <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0c101b; color: #e2e8f0; padding: 40px; }
        .card { background: #161f30; padding: 30px; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); max-width: 650px; margin: 0 auto; border: 1px solid #233554; }
        h1 { color: #0084ff; margin-top: 0; }
        ul { padding-left: 20px; }
        code { background: #0c101b; padding: 4px 8px; border-radius: 4px; font-family: monospace; color: #38bdf8; }
        .footer { text-align: center; margin-top: 30px; font-size: 0.9em; color: #64748b; }
        .badge { background: #10b981; color: white; padding: 4px 8px; border-radius: 4px; font-size: 0.8em; font-weight: bold; }
      </style>
    </head>
    <body>
      <div class="card">
        <h1>FastDrop TV Server <span class="badge">ACTIVE</span></h1>
        <p>Your local media streaming server is up and running!</p>
        
        <h3>1. Connection Addresses</h3>
        <p>Enter one of these IPs in your Tizen TV App:</p>
        <ul>
          ${ipList || '<li>No active Wi-Fi/LAN connection found. Please check your network.</li>'}
        </ul>
        
        <h3>2. Configuration</h3>
        <ul>
          <li><strong>Port:</strong> <code>${config.port}</code></li>
          <li><strong>Shared Folder:</strong> <code>${resolvedSharedFolder}</code></li>
        </ul>

        <h3>3. Developer/Browser Client</h3>
        <p>To run the app directly in a web browser (Laptop/TV browser):<br>
           <a href="/client/index.html" style="color: #0084ff;">http://localhost:${config.port}/client/index.html</a>
        </p>
      </div>
      <div class="footer">FastDrop TV &bull; Developed for Samsung Tizen OS</div>
    </body>
    </html>
  `;
  res.send(responseHtml);
});

// Endpoint: GET /api/files
// Returns JSON array of folders and supported media files
app.get('/api/files', (req, res) => {
  const relativeSubpath = req.query.path || '';
  const targetDirectory = path.join(resolvedSharedFolder, relativeSubpath);

  // Security check: Path traversal prevention
  if (!isPathSafe(targetDirectory)) {
    log(`Security Warning: Blocked path traversal attempt to "${targetDirectory}"`);
    return res.status(403).json({ error: 'Access denied: Path lies outside shared directory' });
  }

  // Check if directory exists
  if (!fs.existsSync(targetDirectory)) {
    return res.status(404).json({ error: 'Directory not found' });
  }

  try {
    const stats = fs.statSync(targetDirectory);
    if (!stats.isDirectory()) {
      return res.status(400).json({ error: 'Path is a file, not a directory' });
    }

    const items = fs.readdirSync(targetDirectory);
    const folders = [];
    const files = [];

    // Supported file extensions
    const supportedVideo = ['.mp4', '.mkv', '.webm', '.avi'];
    const supportedAudio = ['.mp3', '.wav', '.m4a'];
    const supportedImage = ['.jpg', '.jpeg', '.png', '.webp'];
    const supportedPdf = ['.pdf'];
    const allSupported = [...supportedVideo, ...supportedAudio, ...supportedImage, ...supportedPdf];

    // First pass: scan for video files and identify matching poster images
    const detectedPosters = new Set();
    const videoPosters = {};

    for (const item of items) {
      if (item.startsWith('.')) continue;
      const itemPath = path.join(targetDirectory, item);
      const ext = path.extname(item).toLowerCase();
      if (supportedVideo.includes(ext)) {
        const base = itemPath.slice(0, -ext.length);
        const relativeItemPath = path.relative(resolvedSharedFolder, itemPath).replace(/\\/g, '/');
        const relativeBase = relativeItemPath.slice(0, -ext.length);

        if (fs.existsSync(base + '.jpg')) {
          videoPosters[relativeItemPath] = relativeBase + '.jpg';
          detectedPosters.add(path.resolve(base + '.jpg'));
        } else if (fs.existsSync(base + '.jpeg')) {
          videoPosters[relativeItemPath] = relativeBase + '.jpeg';
          detectedPosters.add(path.resolve(base + '.jpeg'));
        } else if (fs.existsSync(base + '.png')) {
          videoPosters[relativeItemPath] = relativeBase + '.png';
          detectedPosters.add(path.resolve(base + '.png'));
        }
      }
    }

    for (const item of items) {
      // Ignore hidden files (starting with dot)
      if (item.startsWith('.')) continue;

      const itemPath = path.join(targetDirectory, item);
      const relativeItemPath = path.relative(resolvedSharedFolder, itemPath).replace(/\\/g, '/');

      try {
        const itemStats = fs.statSync(itemPath);

        if (itemStats.isDirectory()) {
          folders.push({
            name: item,
            type: 'folder',
            relativePath: relativeItemPath,
            size: 0,
            sizeFormatted: '--',
            extension: '',
            mimeType: 'directory'
          });
        } else if (itemStats.isFile()) {
          const ext = path.extname(item).toLowerCase();
          
          // Filter to include only supported media types
          if (allSupported.includes(ext)) {
            // Exclude raw images that are already used as posters
            if (supportedImage.includes(ext) && detectedPosters.has(path.resolve(itemPath))) {
              continue;
            }

            let mediaType = 'file';
            let subtitlePath = null;
            let posterPath = null;

            if (supportedVideo.includes(ext)) {
              mediaType = 'video';
              // Check if a subtitle file (.vtt or .srt) exists next to this video file
              const vttPath = itemPath.slice(0, -ext.length) + '.vtt';
              const srtPath = itemPath.slice(0, -ext.length) + '.srt';
              
              if (fs.existsSync(vttPath)) {
                subtitlePath = relativeItemPath.slice(0, -ext.length) + '.vtt';
              } else if (fs.existsSync(srtPath)) {
                subtitlePath = relativeItemPath.slice(0, -ext.length) + '.srt';
              }

              // Retrieve the poster path identified in the first pass
              posterPath = videoPosters[relativeItemPath] || null;
            }
            else if (supportedAudio.includes(ext)) mediaType = 'audio';
            else if (supportedImage.includes(ext)) mediaType = 'image';
            else if (supportedPdf.includes(ext)) mediaType = 'pdf';

            files.push({
              name: item,
              type: mediaType,
              relativePath: relativeItemPath,
              size: itemStats.size,
              sizeFormatted: formatBytes(itemStats.size),
              extension: ext,
              mimeType: getMimeType(itemPath),
              subtitlePath: subtitlePath,
              posterPath: posterPath
            });
          }
        }
      } catch (err) {
        log(`Error reading stats for item "${item}": ${err.message}`);
      }
    }

    // Sort folders alphabetically, files alphabetically, then merge
    folders.sort((a, b) => a.name.localeCompare(b.name));
    files.sort((a, b) => a.name.localeCompare(b.name));

    res.json([...folders, ...files]);
  } catch (error) {
    log(`API Error listing directory: ${error.message}`);
    res.status(500).json({ error: 'Failed to read directory' });
  }
});

// Endpoint: GET /stream
// Streams file. Supports chunked reading and HTTP Range Requests (essential for seeking/forwarding on TV players)
app.get('/stream', (req, res) => {
  const relativeFilePath = req.query.path;
  if (!relativeFilePath) {
    return res.status(400).send('Missing file path');
  }

  const filePath = path.join(resolvedSharedFolder, relativeFilePath);

  // Security check: Path traversal prevention
  if (!isPathSafe(filePath)) {
    log(`Security Warning: Blocked stream request outside shared directory: "${filePath}"`);
    return res.status(403).send('Access denied');
  }

  if (!fs.existsSync(filePath)) {
    return res.status(404).send('File not found');
  }

  try {
    const stat = fs.statSync(filePath);
    if (!stat.isFile()) {
      return res.status(400).send('Path is a directory');
    }

    // Convert SRT to WebVTT on-the-fly for Tizen player compatibility
    if (filePath.toLowerCase().endsWith('.srt')) {
      try {
        let content = fs.readFileSync(filePath, 'utf8');
        content = 'WEBVTT\n\n' + content.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
        res.writeHead(200, {
          'Content-Type': 'text/vtt',
          'Access-Control-Allow-Origin': '*'
        });
        return res.end(content);
      } catch (err) {
        log(`SRT Subtitle Conversion Error: ${err.message}`);
        return res.status(500).send('Subtitle conversion failed');
      }
    }

    const fileSize = stat.size;
    const mimeType = getMimeType(filePath);
    const range = req.headers.range;
    const download = req.query.download === 'true';

    // Handle range requests
    if (range) {
      const parts = range.replace(/bytes=/, '').split('-');
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

      // Check range validity
      if (start >= fileSize || end >= fileSize || start > end) {
        res.writeHead(416, {
          'Content-Range': `bytes */${fileSize}`,
          'Accept-Ranges': 'bytes'
        });
        return res.end();
      }

      const chunksize = (end - start) + 1;
      const fileStream = fs.createReadStream(filePath, { start, end });
      
      const headers = {
        'Content-Range': `bytes ${start}-${end}/${fileSize}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunksize,
        'Content-Type': mimeType,
      };

      if (download) {
        const basename = path.basename(filePath);
        const safeFilename = basename.replace(/"/g, '\\"');
        const encodedFilename = encodeURIComponent(basename).replace(/['()]/g, escape).replace(/\*/g, '%2A');
        headers['Content-Disposition'] = `attachment; filename="${safeFilename}"; filename*=UTF-8''${encodedFilename}`;
      }

      res.writeHead(206, headers);
      fileStream.pipe(res);
    } else {
      // Standard HTTP response for complete file download/stream
      const headers = {
        'Content-Length': fileSize,
        'Content-Type': mimeType,
        'Accept-Ranges': 'bytes'
      };

      if (download) {
        const basename = path.basename(filePath);
        const safeFilename = basename.replace(/"/g, '\\"');
        const encodedFilename = encodeURIComponent(basename).replace(/['()]/g, escape).replace(/\*/g, '%2A');
        headers['Content-Disposition'] = `attachment; filename="${safeFilename}"; filename*=UTF-8''${encodedFilename}`;
      }

      res.writeHead(200, headers);
      fs.createReadStream(filePath).pipe(res);
    }
  } catch (error) {
    log(`Streaming Error: ${error.message}`);
    if (!res.headersSent) {
      res.status(500).send('Internal server error');
    }
  }
});

// Endpoint: POST /api/upload
// Receives raw binary stream for high-speed file upload
app.post('/api/upload', (req, res) => {
  const fileName = req.query.name;
  const relativeSubpath = req.query.path || '';
  if (!fileName) {
    return res.status(400).send('Missing file name');
  }

  const targetPath = path.join(resolvedSharedFolder, relativeSubpath, fileName);

  // Security check: Path traversal prevention
  if (!isPathSafe(targetPath)) {
    log(`Security Warning: Blocked upload request outside shared directory: "${targetPath}"`);
    return res.status(403).send('Access denied');
  }

  log(`Receiving upload: "${fileName}" -> "${targetPath}"`);

  const writeStream = fs.createWriteStream(targetPath);
  req.pipe(writeStream);

  writeStream.on('finish', () => {
    log(`Upload complete: "${fileName}"`);
    res.json({ success: true, message: 'File uploaded successfully' });
  });

  req.on('error', (err) => {
    log(`Upload stream error for "${fileName}": ${err.message}`);
    writeStream.destroy();
    if (!res.headersSent) {
      res.status(500).send(`Upload failed: ${err.message}`);
    }
  });

  writeStream.on('error', (err) => {
    log(`Upload write error for "${fileName}": ${err.message}`);
    if (!res.headersSent) {
      res.status(500).send(`Upload failed: ${err.message}`);
    }
  });
});

// Endpoint: DELETE /api/delete
// Deletes a file or directory from the shared folder
app.delete('/api/delete', (req, res) => {
  const relativePath = req.query.path;
  if (!relativePath) {
    return res.status(400).send('Missing path parameter');
  }

  const targetPath = path.join(resolvedSharedFolder, relativePath);

  // Security check: Path traversal prevention
  if (!isPathSafe(targetPath)) {
    log(`Security Warning: Blocked delete request outside shared directory: "${targetPath}"`);
    return res.status(403).send('Access denied');
  }

  if (!fs.existsSync(targetPath)) {
    return res.status(404).send('File not found');
  }

  try {
    const stats = fs.statSync(targetPath);
    if (stats.isDirectory()) {
      fs.rmSync(targetPath, { recursive: true, force: true });
      log(`Directory deleted: "${relativePath}"`);
    } else {
      fs.unlinkSync(targetPath);
      log(`File deleted: "${relativePath}"`);
    }
    res.json({ success: true, message: 'Deleted successfully' });
  } catch (error) {
    log(`Delete error for "${relativePath}": ${error.message}`);
    res.status(500).send(`Delete failed: ${error.message}`);
  }
});

// Start listening
app.listen(config.port, '0.0.0.0', () => {
  const ips = getLocalIPs();
  console.log('======================================================');
  console.log(`🚀 Fast File Share Server is running on port ${config.port}`);
  console.log('======================================================');
  console.log(`Shared Directory: ${resolvedSharedFolder}`);
  console.log('\nAvailable Local network IPs:');
  ips.forEach(ip => {
    console.log(` 👉 http://${ip}:${config.port}`);
  });
  console.log('\nDirect Browser Share Link:');
  ips.forEach(ip => {
    console.log(` 🔗 http://${ip}:${config.port}/client/index.html`);
  });
  console.log('======================================================');
});
