/**
 * Animated Capes - Cloudflare Worker Backend
 * 
 * Host for free on Cloudflare Workers with KV or R2 bindings:
 *   - KV Namespace: CAPES_DB
 *   - R2 Bucket (optional, or store in KV): CAPES_STORAGE
 * 
 * Deploy via Wrangler:
 *   wrangler deploy
 */

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        const path = url.pathname;

        // CORS headers
        const corsHeaders = {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, X-Player-UUID, X-Player-Name, X-Cape-Hash, X-File-Name'
        };

        if (request.method === 'OPTIONS') {
            return new Response(null, { status: 204, headers: corsHeaders });
        }

        // GET /api/capes/:uuid
        if (request.method === 'GET' && path.startsWith('/api/capes/') && !path.startsWith('/api/capes/file/')) {
            const uuid = path.replace('/api/capes/', '').trim().toLowerCase();
            const raw = await env.CAPES_DB.get(`player:${uuid}`);
            if (!raw) {
                return new Response(JSON.stringify({ error: 'No cape found' }), {
                    status: 404,
                    headers: { ...corsHeaders, 'Content-Type': 'application/json' }
                });
            }
            const data = JSON.parse(raw);
            const fileUrl = `${url.origin}/api/capes/file/${data.hash}`;
            return new Response(JSON.stringify({ ...data, url: fileUrl }), {
                status: 200,
                headers: { ...corsHeaders, 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=60' }
            });
        }

        // GET /api/capes/file/:hash
        if (request.method === 'GET' && path.startsWith('/api/capes/file/')) {
            const hash = path.replace('/api/capes/file/', '').trim().toLowerCase();
            const fileData = await env.CAPES_DB.get(`file:${hash}`, { type: 'arrayBuffer' });
            if (!fileData) {
                return new Response(JSON.stringify({ error: 'File not found' }), {
                    status: 404,
                    headers: { ...corsHeaders, 'Content-Type': 'application/json' }
                });
            }

            return new Response(fileData, {
                status: 200,
                headers: {
                    ...corsHeaders,
                    'Content-Type': 'image/webp',
                    'Cache-Control': 'public, max-age=31536000, immutable'
                }
            });
        }

        // POST /api/capes/:uuid
        if (request.method === 'POST' && path.startsWith('/api/capes/')) {
            const uuid = path.replace('/api/capes/', '').trim().toLowerCase();
            const username = request.headers.get('x-player-name') || 'Player';
            const body = await request.arrayBuffer();

            if (!body || body.byteLength === 0) {
                return new Response(JSON.stringify({ error: 'Empty file' }), {
                    status: 400,
                    headers: { ...corsHeaders, 'Content-Type': 'application/json' }
                });
            }

            // Compute SHA-256
            const hashBuffer = await crypto.subtle.digest('SHA-256', body);
            const hashArray = Array.from(new Uint8Array(hashBuffer));
            const hash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');

            // Store file
            await env.CAPES_DB.put(`file:${hash}`, body);

            // Store player mapping
            const playerRecord = {
                uuid,
                username,
                hash,
                updatedAt: Math.floor(Date.now() / 1000)
            };
            await env.CAPES_DB.put(`player:${uuid}`, JSON.stringify(playerRecord));

            const fileUrl = `${url.origin}/api/capes/file/${hash}`;
            return new Response(JSON.stringify({ success: true, uuid, hash, url: fileUrl }), {
                status: 200,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // DELETE /api/capes/:uuid
        if (request.method === 'DELETE' && path.startsWith('/api/capes/')) {
            const uuid = path.replace('/api/capes/', '').trim().toLowerCase();
            await env.CAPES_DB.delete(`player:${uuid}`);
            return new Response(JSON.stringify({ success: true }), {
                status: 200,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        return new Response(JSON.stringify({ error: 'Not found' }), {
            status: 404,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
    }
};
