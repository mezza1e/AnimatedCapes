/**
 * Animated Capes - Lightweight Cosmetics Backend Server
 * 
 * Routes:
 *   GET    /api/capes/:uuid       - Returns player's cape metadata JSON { uuid, hash, url }
 *   GET    /api/capes/file/:hash  - Serves binary image file (WebP/GIF/PNG)
 *   POST   /api/capes/:uuid       - Uploads/updates cape for a player UUID
 *   DELETE /api/capes/:uuid       - Clears cape for a player UUID
 * 
 * Run: node server.js [PORT]
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || process.argv[2] || '8089', 10);
const DATA_DIR = path.join(__dirname, 'data');
const FILES_DIR = path.join(DATA_DIR, 'files');
const DB_FILE = path.join(DATA_DIR, 'capes.json');

fs.mkdirSync(FILES_DIR, { recursive: true });

// Load player DB: { uuid: { hash, filename, updatedAt, username } }
let db = {};
if (fs.existsSync(DB_FILE)) {
    try {
        db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    } catch (e) {
        console.error('Failed to parse database, starting fresh:', e.message);
    }
}

function saveDb() {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
}

const server = http.createServer((req, res) => {
    // Enable CORS for web dashboards / external clients
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Player-UUID, X-Player-Name, X-Cape-Hash, X-File-Name');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    // GET /api/capes/:uuid
    if (req.method === 'GET' && pathname.startsWith('/api/capes/') && !pathname.startsWith('/api/capes/file/')) {
        const uuid = pathname.replace('/api/capes/', '').trim().toLowerCase();
        const entry = db[uuid];
        if (!entry) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'No cape found for this player' }));
            return;
        }

        const host = req.headers.host || `localhost:${PORT}`;
        const fileUrl = `http://${host}/api/capes/file/${entry.hash}`;
        res.writeHead(200, {
            'Content-Type': 'application/json',
            'Cache-Control': 'public, max-age=60'
        });
        res.end(JSON.stringify({
            uuid: entry.uuid || uuid,
            username: entry.username || 'Player',
            hash: entry.hash,
            url: fileUrl,
            updatedAt: entry.updatedAt
        }));
        return;
    }

    // GET /api/capes/file/:hash
    if (req.method === 'GET' && pathname.startsWith('/api/capes/file/')) {
        const hash = pathname.replace('/api/capes/file/', '').trim().toLowerCase();
        // Look for file with any extension in files dir
        const files = fs.readdirSync(FILES_DIR);
        const match = files.find(f => f.startsWith(hash));
        if (!match) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'File not found' }));
            return;
        }

        const filePath = path.join(FILES_DIR, match);
        const stat = fs.statSync(filePath);
        let contentType = 'image/webp';
        if (match.endsWith('.gif')) contentType = 'image/gif';
        else if (match.endsWith('.png')) contentType = 'image/png';
        else if (match.endsWith('.avif')) contentType = 'image/avif';

        res.writeHead(200, {
            'Content-Type': contentType,
            'Content-Length': stat.size,
            'Cache-Control': 'public, max-age=31536000, immutable'
        });
        fs.createReadStream(filePath).pipe(res);
        return;
    }

    // POST /api/capes/:uuid
    if (req.method === 'POST' && pathname.startsWith('/api/capes/')) {
        const uuid = pathname.replace('/api/capes/', '').trim().toLowerCase();
        const username = req.headers['x-player-name'] || 'Player';
        const rawFileName = req.headers['x-file-name'] || 'cape.webp';
        let ext = path.extname(rawFileName).toLowerCase() || '.webp';

        const chunks = [];
        let totalSize = 0;
        const MAX_SIZE = 5 * 1024 * 1024; // 5MB limit

        req.on('data', chunk => {
            totalSize += chunk.length;
            if (totalSize > MAX_SIZE) {
                res.writeHead(413, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'File size exceeds 5MB limit' }));
                req.destroy();
                return;
            }
            chunks.push(chunk);
        });

        req.on('end', () => {
            if (totalSize === 0) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Empty file' }));
                return;
            }

            const buffer = Buffer.concat(chunks);
            const hash = crypto.createHash('sha256').update(buffer).digest('hex');

            // Save file if not already stored
            const targetFile = path.join(FILES_DIR, `${hash}${ext}`);
            if (!fs.existsSync(targetFile)) {
                fs.writeFileSync(targetFile, buffer);
            }

            // Update DB
            db[uuid] = {
                uuid,
                username,
                hash,
                filename: rawFileName,
                updatedAt: Math.floor(Date.now() / 1000)
            };
            saveDb();

            const host = req.headers.host || `localhost:${PORT}`;
            const fileUrl = `http://${host}/api/capes/file/${hash}`;

            console.log(`[AnimatedCapes Server] Uploaded cape for ${username} (${uuid}) - Hash: ${hash.substring(0, 8)}... (${(totalSize / 1024).toFixed(1)} KB)`);

            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                success: true,
                uuid,
                hash,
                url: fileUrl
            }));
        });
        return;
    }

    // DELETE /api/capes/:uuid
    if (req.method === 'DELETE' && pathname.startsWith('/api/capes/')) {
        const uuid = pathname.replace('/api/capes/', '').trim().toLowerCase();
        if (db[uuid]) {
            console.log(`[AnimatedCapes Server] Removed cape for ${uuid}`);
            delete db[uuid];
            saveDb();
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Cape cleared' }));
        return;
    }

    // Default 404
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Not found' }));
});

server.listen(PORT, () => {
    console.log(`=================================================`);
    console.log(`  Animated Capes Cloud API running on port ${PORT}`);
    console.log(`  API Endpoint: http://localhost:${PORT}/api/capes`);
    console.log(`=================================================`);
});
