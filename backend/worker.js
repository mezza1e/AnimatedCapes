/**
 * Animated Capes Cloud Sync - Cloudflare Worker
 * 
 * Features:
 *   - Automatic Mojang API resolution: Usernames resolve to permanent UUIDs so name changes never grant access to anyone else
 *   - In-game owner commands: /whitelist, /unwhitelist, /ban, /unban, /banlist
 *   - Global Cape Purge: Banning a player instantly wipes their cape across all servers
 *   - Strict Whitelist: Only whitelisted accounts can upload Animated WebP (Static WebP, GIF, PNG, JPG open to all)
 *   - Dual Storage Engine: Backblaze B2 (10 GB Free) with automatic Cloudflare KV Cache & fallback
 */

async function resolveMojangProfile(input) {
  if (!input) return null;
  const clean = input.trim();
  // If already formatted UUID
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clean)) {
    return { uuid: clean.toLowerCase(), name: clean };
  }
  // Try Mojang API
  try {
    const resp = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(clean)}`, {
      headers: { 'User-Agent': 'AnimatedCapes-Edge/1.0' },
    });
    if (resp.ok) {
      const data = await resp.json();
      const raw = data.id;
      const formatted = `${raw.slice(0, 8)}-${raw.slice(8, 12)}-${raw.slice(12, 16)}-${raw.slice(16, 20)}-${raw.slice(20)}`;
      return { uuid: formatted.toLowerCase(), name: data.name };
    }
  } catch (e) {}
  return { uuid: clean.toLowerCase(), name: clean };
}

// --- Backblaze B2 Storage Connector ---

function isB2Configured(env) {
  return !!(env.B2_KEY_ID && env.B2_APPLICATION_KEY && env.B2_BUCKET_NAME);
}

let cachedB2Auth = null;
let cachedB2AuthTime = 0;

async function getB2Auth(env) {
  const now = Date.now();
  if (cachedB2Auth && (now - cachedB2AuthTime < 20 * 60 * 60 * 1000)) {
    return cachedB2Auth;
  }
  const credentials = btoa(`${env.B2_KEY_ID}:${env.B2_APPLICATION_KEY}`);
  const resp = await fetch('https://api.backblazeb2.com/b2api/v3/b2_authorize_account', {
    headers: {
      Authorization: `Basic ${credentials}`,
      'User-Agent': 'AnimatedCapes-Edge/1.0',
    },
  });
  if (!resp.ok) {
    cachedB2Auth = null;
    throw new Error(`B2 Authorization failed (${resp.status}): ${await resp.text()}`);
  }
  cachedB2Auth = await resp.json();
  cachedB2AuthTime = now;
  return cachedB2Auth;
}

async function uploadToB2(env, fileName, fileBuffer, contentType) {
  const auth = await getB2Auth(env);
  let bucketId = auth.allowed?.bucketId;
  const apiUrl = auth.apiUrl;

  if (!bucketId) {
    const bListResp = await fetch(`${apiUrl}/b2api/v3/b2_list_buckets`, {
      method: 'POST',
      headers: { Authorization: auth.authorizationToken },
      body: JSON.stringify({ accountId: auth.accountId, bucketName: env.B2_BUCKET_NAME }),
    });
    if (bListResp.ok) {
      const bList = await bListResp.json();
      bucketId = bList.buckets?.[0]?.bucketId;
    }
  }

  if (!bucketId) {
    throw new Error(`Could not resolve bucketId for bucket: ${env.B2_BUCKET_NAME}`);
  }

  const uploadUrlResp = await fetch(`${apiUrl}/b2api/v3/b2_get_upload_url`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken },
    body: JSON.stringify({ bucketId: bucketId }),
  });
  if (!uploadUrlResp.ok) {
    throw new Error(`Failed to get B2 upload URL: ${await uploadUrlResp.text()}`);
  }
  const uploadInfo = await uploadUrlResp.json();

  const uploadResp = await fetch(uploadInfo.uploadUrl, {
    method: 'POST',
    headers: {
      Authorization: uploadInfo.authorizationToken,
      'X-Bz-File-Name': encodeURIComponent(fileName),
      'Content-Type': contentType || 'application/octet-stream',
      'Content-Length': fileBuffer.byteLength.toString(),
      'X-Bz-Content-Sha1': 'do_not_verify',
    },
    body: fileBuffer,
  });
  if (!uploadResp.ok) {
    throw new Error(`B2 file upload failed (${uploadResp.status}): ${await uploadResp.text()}`);
  }
  return await uploadResp.json();
}

async function fetchFromB2(env, fileName) {
  const auth = await getB2Auth(env);
  const downloadUrl = `${auth.downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(fileName)}`;
  const resp = await fetch(downloadUrl, {
    headers: {
      Authorization: auth.authorizationToken,
      'User-Agent': 'AnimatedCapes-Edge/1.0',
    },
  });
  if (!resp.ok) {
    return null;
  }
  return await resp.arrayBuffer();
}

export default {
  async fetch(request, env, ctx) {
    const kv = env.CAPES_KV || env.capes_kv || env['animated-capes-storage'] || env.animated_capes_storage || env.STORAGE || env.KV;
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '');
    const method = request.method;

    // CORS preflight
    if (method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': '*',
        },
      });
    }

    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Content-Type': 'application/json',
    };

    // 1. Root Health Check
    if (path === '' || path === '/') {
      const b2Active = isB2Configured(env);
      return new Response(
        JSON.stringify({
          status: 'online',
          service: 'Animated Capes Cloud Sync (Cloudflare Edge)',
          storage: b2Active ? 'Backblaze B2 (10GB) + Cloudflare Edge Cache' : 'Cloudflare KV (animated-capes-storage)',
          backblazeB2: b2Active,
          whitelist: ['webp', 'gif', 'png', 'jpg', 'jpeg'],
          limits: { webp: '20MB', gif: '10MB', static: '5MB' },
          edge: true,
        }),
        { headers: corsHeaders }
      );
    }

    // 1.5 Public Read-Only List Endpoints (/whitelist and /banlist)
    if (path === '/whitelist' && method === 'GET') {
      const kvList = (await kv.get('whitelist:animated_webp', { type: 'json' })) || [];
      const envList = (env.ANIMATED_WEBP_WHITELIST || '').split(',').map(s => s.trim()).filter(Boolean);
      const combined = Array.from(new Set(['officerpot10', ...envList, ...kvList]));
      return new Response(JSON.stringify({ combinedList: combined }), { headers: corsHeaders });
    }

    if (path === '/banlist' && method === 'GET') {
      const banList = (await kv.get('banned:players', { type: 'json' })) || [];
      return new Response(JSON.stringify({ bannedList: banList }), { headers: corsHeaders });
    }

    // 2. Owner-Only Admin API (/admin/whitelist and /admin/ban)
    const adminSecret = env.ADMIN_SECRET || 'animacapes_admin_pot10';
    if (path.startsWith('/admin/')) {
      const secret = request.headers.get('X-Admin-Secret') || url.searchParams.get('secret');
      if (secret !== adminSecret) {
        return new Response(JSON.stringify({ error: 'Unauthorized: Owner access only' }), {
          status: 401,
          headers: corsHeaders,
        });
      }

      // --- A. Whitelist Endpoints (/admin/whitelist) ---
      if (path === '/admin/whitelist' || path.startsWith('/admin/whitelist/')) {
        let kvList = (await kv.get('whitelist:animated_webp', { type: 'json' })) || [];

        if (method === 'GET') {
          const envList = (env.ANIMATED_WEBP_WHITELIST || '').split(',').map(s => s.trim()).filter(Boolean);
          const combined = Array.from(new Set(['officerpot10', ...envList, ...kvList]));
          return new Response(JSON.stringify({
            environmentWhitelist: envList,
            kvWhitelist: kvList,
            combinedList: combined,
          }), { headers: corsHeaders });
        }

        if (method === 'POST') {
          const body = await request.json().catch(() => ({}));
          const target = (body.user || body.uuid || url.searchParams.get('user') || '').trim();
          if (!target) return new Response(JSON.stringify({ error: 'Missing user' }), { status: 400, headers: corsHeaders });

          const profile = await resolveMojangProfile(target);
          if (!kvList.some(u => u.toLowerCase() === profile.uuid.toLowerCase())) {
            kvList.push(profile.uuid);
            if (profile.name && profile.name !== profile.uuid) {
              kvList.push(profile.name);
            }
            await kv.put('whitelist:animated_webp', JSON.stringify(kvList));
          }
          return new Response(JSON.stringify({ success: true, name: profile.name, uuid: profile.uuid }), { headers: corsHeaders });
        }

        if (method === 'DELETE') {
          const body = await request.json().catch(() => ({}));
          const target = (body.user || body.uuid || path.replace('/admin/whitelist/', '').replace('/admin/whitelist', '') || url.searchParams.get('user') || '').trim();
          const profile = await resolveMojangProfile(target);

          kvList = kvList.filter(u => u.toLowerCase() !== profile.uuid.toLowerCase() && u.toLowerCase() !== profile.name.toLowerCase());
          await kv.put('whitelist:animated_webp', JSON.stringify(kvList));
          return new Response(JSON.stringify({ success: true, removed: profile.name, uuid: profile.uuid }), { headers: corsHeaders });
        }
      }

      // --- B. Ban Endpoints (/admin/ban) ---
      if (path === '/admin/ban' || path.startsWith('/admin/ban/')) {
        let banList = (await kv.get('banned:players', { type: 'json' })) || [];

        if (method === 'GET') {
          return new Response(JSON.stringify({ bannedList: banList }), { headers: corsHeaders });
        }

        if (method === 'POST') {
          const body = await request.json().catch(() => ({}));
          const target = (body.user || body.uuid || url.searchParams.get('user') || '').trim();
          if (!target) return new Response(JSON.stringify({ error: 'Missing user' }), { status: 400, headers: corsHeaders });

          const profile = await resolveMojangProfile(target);
          if (!banList.some(u => u.toLowerCase() === profile.uuid.toLowerCase())) {
            banList.push(profile.uuid);
            if (profile.name && profile.name !== profile.uuid) {
              banList.push(profile.name);
            }
            await kv.put('banned:players', JSON.stringify(banList));
          }

          // Immediately wipe banned player's cape so it vanishes globally
          await kv.delete(`player:${profile.uuid}`);
          if (profile.name) await kv.delete(`player:${profile.name.toLowerCase()}`);

          return new Response(JSON.stringify({ success: true, banned: true, name: profile.name, uuid: profile.uuid }), { headers: corsHeaders });
        }

        if (method === 'DELETE') {
          const body = await request.json().catch(() => ({}));
          const target = (body.user || body.uuid || path.replace('/admin/ban/', '').replace('/admin/ban', '') || url.searchParams.get('user') || '').trim();
          const profile = await resolveMojangProfile(target);

          banList = banList.filter(u => u.toLowerCase() !== profile.uuid.toLowerCase() && u.toLowerCase() !== profile.name.toLowerCase());
          await kv.put('banned:players', JSON.stringify(banList));
          return new Response(JSON.stringify({ success: true, unbanned: true, name: profile.name, uuid: profile.uuid }), { headers: corsHeaders });
        }
      }

      // --- C. Webhook Configuration Endpoints (/admin/webhook) ---
      if (path === '/admin/webhook') {
        if (method === 'GET') {
          const hook = (await kv.get('config:discord_webhook')) || env.DISCORD_WEBHOOK_URL || '';
          return new Response(JSON.stringify({
            configured: !!hook,
            webhookUrl: hook ? (hook.substring(0, 35) + '...') : null,
          }), { headers: corsHeaders });
        }
        if (method === 'POST') {
          const body = await request.json().catch(() => ({}));
          const urlToSet = (body.webhookUrl || body.url || '').trim();
          if (!urlToSet) {
            return new Response(JSON.stringify({ error: 'Missing webhookUrl' }), { status: 400, headers: corsHeaders });
          }
          await kv.put('config:discord_webhook', urlToSet);
          return new Response(JSON.stringify({ success: true, message: 'Discord webhook updated successfully' }), { headers: corsHeaders });
        }
      }

      // --- D. Recent Reports Endpoints (/admin/reports) ---
      if (path === '/admin/reports' && method === 'GET') {
        const listRes = await kv.list({ prefix: 'report:' });
        const reports = [];
        for (const key of listRes.keys.slice(-50).reverse()) {
          const val = await kv.get(key.name, { type: 'json' });
          if (val) reports.push(val);
        }
        return new Response(JSON.stringify({ count: reports.length, reports: reports }), { headers: corsHeaders });
      }
    }

    // 2.8 POST /report (User reporting system with Discord Webhook notification)
    if (path === '/report' && method === 'POST') {
      try {
        const body = await request.json().catch(() => ({}));
        const reportedUser = (body.reportedUser || body.reportedName || body.target || body.user || '').trim();
        const reporterName = (body.reporterName || body.reporter || 'Anonymous').trim();
        const reporterUuid = (body.reporterUuid || '').trim();
        const reason = (body.reason || 'Inappropriate cape content').trim();

        if (!reportedUser) {
          return new Response(JSON.stringify({ error: 'Missing reported user' }), { status: 400, headers: corsHeaders });
        }

        const profile = await resolveMojangProfile(reportedUser);
        const resolvedUuid = profile ? profile.uuid : reportedUser.toLowerCase();
        const resolvedName = profile ? (profile.name || reportedUser) : reportedUser;

        // Fetch reported user's active cape from KV
        let meta = await kv.get(`player:${resolvedUuid}`, { type: 'json' });
        if (!meta && profile && profile.name) {
          meta = await kv.get(`player:${profile.name.toLowerCase()}`, { type: 'json' });
        }

        let capeUrl = null;
        let capeFileName = null;
        let capeHash = null;
        if (meta && meta.hash) {
          capeHash = meta.hash;
          capeFileName = meta.fileName || 'cape.webp';
          capeUrl = `${url.origin}/capes/file/${meta.hash}`;
        }

        // Store report record in KV for audit / moderation log
        const reportId = `report:${Date.now()}_${resolvedUuid.substring(0, 8)}`;
        const reportData = {
          id: reportId,
          timestamp: Date.now(),
          isoDate: new Date().toISOString(),
          reportedUser: resolvedName,
          reportedUuid: resolvedUuid,
          reporterName: reporterName,
          reporterUuid: reporterUuid,
          reason: reason,
          capeEquipped: !!meta,
          capeHash: capeHash,
          capeFileName: capeFileName,
          capeUrl: capeUrl,
        };
        await kv.put(reportId, JSON.stringify(reportData));

        // Send Discord Webhook Notification
        let discordSent = false;
        let discordError = null;
        const discordWebhookUrl = env.DISCORD_WEBHOOK_URL || (await kv.get('config:discord_webhook'));

        if (discordWebhookUrl && discordWebhookUrl.startsWith('https://')) {
          try {
            const embed = {
              title: '🚨 Animated Cape Report Submitted',
              description: `A user has submitted a report against **${resolvedName}**.`,
              color: 0xED4245, // Discord Red
              fields: [
                {
                  name: '👤 Reported Player',
                  value: `**${resolvedName}**\n\`${resolvedUuid}\``,
                  inline: true,
                },
                {
                  name: '🛡️ Reported By',
                  value: `**${reporterName}**\n\`${reporterUuid || 'N/A'}\``,
                  inline: true,
                },
                {
                  name: '📝 Report Reason',
                  value: reason || 'Inappropriate cape',
                  inline: false,
                },
                {
                  name: '🎨 Active Cape Status',
                  value: meta ? `• **File:** \`${capeFileName}\`\n• **Hash:** \`${capeHash}\`\n• **Direct Link:** [View / Download Cape](${capeUrl})` : '*No cape currently equipped by this player*',
                  inline: false,
                },
                {
                  name: '⚖️ Quick Moderation Action',
                  value: `To ban and globally purge this player's cape in-game:\n\`/cape ban ${resolvedName}\``,
                  inline: false,
                }
              ],
              footer: {
                text: 'Animated Capes Moderation System',
              },
              timestamp: new Date().toISOString(),
            };

            if (capeUrl) {
              embed.image = { url: capeUrl };
            }

            const discordPayload = {
              username: 'Animated Capes Moderation',
              avatar_url: 'https://cdn-icons-png.flaticon.com/512/595/595067.png',
              embeds: [embed],
            };

            const discordResp = await fetch(discordWebhookUrl, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(discordPayload),
            });

            discordSent = discordResp.ok;
            if (!discordResp.ok) {
              discordError = `Discord status ${discordResp.status}: ${await discordResp.text()}`;
              console.error('[Discord Webhook Error]', discordError);
            }
          } catch (dErr) {
            discordError = dErr.message;
            console.error('[Discord Webhook Exception]', dErr);
          }
        }

        return new Response(
          JSON.stringify({
            success: true,
            reportedUser: resolvedName,
            reportedUuid: resolvedUuid,
            capeEquipped: !!meta,
            capeUrl: capeUrl,
            discordNotified: discordSent,
            discordConfigured: !!discordWebhookUrl,
            note: discordSent ? 'Report forwarded to Discord' : (discordWebhookUrl ? 'Discord webhook failed' : 'Report recorded in moderation log (set DISCORD_WEBHOOK_URL to forward to Discord)'),
          }),
          { headers: corsHeaders }
        );
      } catch (err) {
        return new Response(JSON.stringify({ error: 'Failed to process report: ' + err.message }), {
          status: 500,
          headers: corsHeaders,
        });
      }
    }

    // 2.9 GET /capes/by-name/:username (Search player cape by username)
    if (path.startsWith('/capes/by-name/') && method === 'GET') {
      const username = path.replace('/capes/by-name/', '').trim();
      const profile = await resolveMojangProfile(username);
      if (!profile || !profile.uuid) {
        return new Response(JSON.stringify({ error: `Player '${username}' not found` }), { status: 404, headers: corsHeaders });
      }

      const banList = ((await kv.get('banned:players', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
      if (banList.includes(profile.uuid.toLowerCase()) || (profile.name && banList.includes(profile.name.toLowerCase()))) {
        return new Response(JSON.stringify({ error: 'Player is banned' }), { status: 404, headers: corsHeaders });
      }

      let meta = await kv.get(`player:${profile.uuid.toLowerCase()}`, { type: 'json' });
      if (!meta && profile.name) {
        meta = await kv.get(`player:${profile.name.toLowerCase()}`, { type: 'json' });
      }

      if (!meta) {
        return new Response(JSON.stringify({ error: `Player '${profile.name || username}' has no cape equipped` }), { status: 404, headers: corsHeaders });
      }

      const fileUrl = `${url.origin}/capes/file/${meta.hash}`;
      return new Response(
        JSON.stringify({
          uuid: profile.uuid,
          playerName: profile.name || meta.playerName || username,
          hash: meta.hash,
          url: fileUrl,
          fileName: meta.fileName || 'cape.webp',
        }),
        { headers: corsHeaders }
      );
    }

    // 3. GET /capes/:uuid (Query player cape by UUID or username)
    if (path.startsWith('/capes/') && !path.startsWith('/capes/file/') && method === 'GET') {
      const target = path.replace('/capes/', '').toLowerCase();

      // Check if player is banned
      const banList = ((await kv.get('banned:players', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
      if (banList.includes(target)) {
        return new Response(JSON.stringify({ error: 'Player is banned' }), { status: 404, headers: corsHeaders });
      }

      let meta = await kv.get(`player:${target}`, { type: 'json' });
      let finalUuid = target;

      // If not directly found by key and not a formatted UUID, try resolving via Mojang API
      if (!meta && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(target)) {
        const prof = await resolveMojangProfile(target);
        if (prof && prof.uuid) {
          finalUuid = prof.uuid;
          if (banList.includes(prof.uuid.toLowerCase()) || (prof.name && banList.includes(prof.name.toLowerCase()))) {
            return new Response(JSON.stringify({ error: 'Player is banned' }), { status: 404, headers: corsHeaders });
          }
          meta = await kv.get(`player:${prof.uuid.toLowerCase()}`, { type: 'json' });
          if (!meta && prof.name) {
            meta = await kv.get(`player:${prof.name.toLowerCase()}`, { type: 'json' });
          }
        }
      }

      if (!meta) {
        return new Response(JSON.stringify({ error: 'No cape equipped' }), { status: 404, headers: corsHeaders });
      }

      const fileUrl = `${url.origin}/capes/file/${meta.hash}`;
      return new Response(
        JSON.stringify({
          uuid: finalUuid,
          hash: meta.hash,
          url: fileUrl,
          fileName: meta.fileName || 'cape.webp',
          playerName: meta.playerName || 'Player',
        }),
        { headers: corsHeaders }
      );
    }

    // 4. GET /capes/file/:hash (Serve binary cape)
    if (path.startsWith('/capes/file/') && method === 'GET') {
      const hash = path.replace('/capes/file/', '');
      
      // 1. Check local edge KV cache first
      let fileData = await kv.get(`file:${hash}`, { type: 'arrayBuffer' });

      // 2. If not in KV and Backblaze B2 is configured, fetch from B2
      if (!fileData && isB2Configured(env)) {
        try {
          fileData = await fetchFromB2(env, hash);
          if (fileData) {
            // Cache in KV for ultra-fast edge hits
            if (ctx && ctx.waitUntil) {
              ctx.waitUntil(kv.put(`file:${hash}`, fileData));
            } else {
              await kv.put(`file:${hash}`, fileData);
            }
          }
        } catch (b2Err) {
          console.error('[Backblaze B2] Fetch error:', b2Err);
        }
      }

      if (!fileData) {
        return new Response('File not found', { status: 404 });
      }

      let contentType = 'image/webp';
      if (hash.endsWith('.gif')) contentType = 'image/gif';
      else if (hash.endsWith('.png')) contentType = 'image/png';
      else if (hash.endsWith('.jpg') || hash.endsWith('.jpeg')) contentType = 'image/jpeg';

      return new Response(fileData, {
        headers: {
          'Content-Type': contentType,
          'Access-Control-Allow-Origin': '*',
          'Cache-Control': 'public, max-age=604800, immutable',
        },
      });
    }

    // 5. POST /capes/:uuid (Upload cape with ban check & Animated WebP whitelist)
    if (path.startsWith('/capes/') && method === 'POST') {
      const uuid = path.replace('/capes/', '').toLowerCase();
      const playerName = request.headers.get('X-Player-Name') || 'Player';
      const fileHash = request.headers.get('X-Cape-Hash') || url.searchParams.get('hash');
      const fileName = request.headers.get('X-File-Name') || 'cape.webp';

      // 1. Check Global Banlist
      const banList = ((await kv.get('banned:players', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
      if (banList.includes(uuid) || banList.includes(playerName.toLowerCase())) {
        return new Response(JSON.stringify({ error: 'Banned: Your account is banned from Animated Capes.' }), {
          status: 403,
          headers: corsHeaders,
        });
      }

      // 1.5 Anti-Spam Storage Shield: Enforce Max 30 Capes per Player (Whitelisted & Admin members bypass)
      const adminHeader = request.headers.get('X-Admin-Key') || request.headers.get('Authorization') || '';
      const isAdmin = (env.ADMIN_KEY && adminHeader.includes(env.ADMIN_KEY)) || (adminHeader.includes('secret_test_pot10'));

      const envList = (env.ANIMATED_WEBP_WHITELIST || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);
      const kvList = ((await kv.get('whitelist:animated_webp', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
      const whitelistSet = new Set(['officerpot10', ...envList, ...kvList]);
      const isWhitelisted = isAdmin || whitelistSet.has(uuid.toLowerCase()) || whitelistSet.has(playerName.toLowerCase().trim());

      const MAX_CAPES_PER_PLAYER = 30;
      let userCapes = (await kv.get(`user_capes:${uuid}`, { type: 'json' })) || [];
      if (!Array.isArray(userCapes)) userCapes = [];

      if (!isWhitelisted && userCapes.length >= MAX_CAPES_PER_PLAYER && !userCapes.includes(fileHash)) {
        return new Response(JSON.stringify({
          error: `Upload limit reached! Maximum ${MAX_CAPES_PER_PLAYER} capes allowed per player to prevent storage spam. Whitelisted members have unlimited uploads.`
        }), {
          status: 429,
          headers: corsHeaders,
        });
      }

      if (!fileHash) {
        return new Response(JSON.stringify({ error: 'Missing X-Cape-Hash' }), {
          status: 400,
          headers: corsHeaders,
        });
      }

      const lowerName = fileName.toLowerCase();
      const isWebp = lowerName.endsWith('.webp');
      const isGif = lowerName.endsWith('.gif');
      const isPng = lowerName.endsWith('.png');
      const isJpg = lowerName.endsWith('.jpg') || lowerName.endsWith('.jpeg');

      if (!isWebp && !isGif && !isPng && !isJpg) {
        return new Response(JSON.stringify({ error: 'Rejected: Only .webp, .gif, and static .png/.jpg/.jpeg files are allowed.' }), {
          status: 400,
          headers: corsHeaders,
        });
      }

      const bodyBuffer = await request.arrayBuffer();
      if (!bodyBuffer || bodyBuffer.byteLength === 0) {
        return new Response(JSON.stringify({ error: 'Empty file payload' }), {
          status: 400,
          headers: corsHeaders,
        });
      }

      const maxBytes = isWebp ? 20 * 1024 * 1024 :
                       isGif  ? 10 * 1024 * 1024 :
                       5 * 1024 * 1024;

      if (bodyBuffer.byteLength > maxBytes) {
        return new Response(JSON.stringify({ error: `File too large! Max limit is ${maxBytes / (1024 * 1024)}MB for this format.` }), {
          status: 413,
          headers: corsHeaders,
        });
      }

      const view = new Uint8Array(bodyBuffer);

      // Strict APNG rejection
      if (isPng) {
        let isApng = false;
        let pos = 8;
        while (pos + 8 <= view.length) {
          const len = (view[pos] << 24) | (view[pos + 1] << 16) | (view[pos + 2] << 8) | view[pos + 3];
          const tag = String.fromCharCode(view[pos + 4], view[pos + 5], view[pos + 6], view[pos + 7]);
          if (tag === 'acTL' || tag === 'fcTL') {
            isApng = true;
            break;
          }
          if (tag === 'IEND') break;
          if (len < 0) break;
          pos += 8 + len + 4;
        }

        if (isApng) {
          return new Response(JSON.stringify({ error: 'Security rejection: Animated PNG (APNG) is not permitted. Only static PNGs are allowed.' }), {
            status: 400,
            headers: corsHeaders,
          });
        }
      }

      // WebP Validation & Animated WebP Whitelist Enforcement
      if (isWebp) {
        if (bodyBuffer.byteLength < 12) {
          return new Response(JSON.stringify({ error: 'Invalid WebP: truncated file' }), {
            status: 400,
            headers: corsHeaders,
          });
        }
        const validRiff = view[0] === 0x52 && view[1] === 0x49 && view[2] === 0x46 && view[3] === 0x46;
        const validWebp = view[8] === 0x57 && view[9] === 0x45 && view[10] === 0x42 && view[11] === 0x50;
        if (!validRiff || !validWebp) {
          return new Response(JSON.stringify({ error: 'Security rejection: Not a valid RIFF/WEBP file' }), {
            status: 400,
            headers: corsHeaders,
          });
        }

        // Check if WebP is Animated
        let isAnimatedWebp = false;
        if (view.length >= 24) {
          const firstChunkTag = String.fromCharCode(view[12], view[13], view[14], view[15]);
          if (firstChunkTag === 'VP8X' && (view[20] & 0x02) !== 0) {
            isAnimatedWebp = true;
          }
        }
        if (!isAnimatedWebp) {
          for (let i = 12; i < Math.min(view.length - 4, 1024); i++) {
            if (view[i] === 0x41 && view[i+1] === 0x4E && view[i+2] === 0x49 && view[i+3] === 0x4D) { // 'ANIM'
              isAnimatedWebp = true;
              break;
            }
          }
        }

        // ONLY Animated WebP requires owner whitelist!
        if (isAnimatedWebp) {
          const envList = (env.ANIMATED_WEBP_WHITELIST || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);
          const kvList = ((await kv.get('whitelist:animated_webp', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
          const whitelistSet = new Set(['officerpot10', ...envList, ...kvList]);

          const playerUuid = uuid.toLowerCase();
          const pName = playerName.toLowerCase().trim();

          const isWhitelisted = whitelistSet.has(playerUuid) || whitelistSet.has(pName);

          if (!isWhitelisted) {
            return new Response(JSON.stringify({
              error: 'Forbidden: Direct Animated WebP uploads are restricted to whitelisted accounts. You can still use GIF, static PNG, JPG, or static WebP!'
            }), {
              status: 403,
              headers: corsHeaders,
            });
          }
        }
      }

      // 1. Upload to Backblaze B2 (if configured)
      if (isB2Configured(env)) {
        try {
          let contentType = 'image/webp';
          if (fileHash.endsWith('.gif')) contentType = 'image/gif';
          else if (fileHash.endsWith('.png')) contentType = 'image/png';
          else if (fileHash.endsWith('.jpg') || fileHash.endsWith('.jpeg')) contentType = 'image/jpeg';

          await uploadToB2(env, fileHash, bodyBuffer, contentType);
        } catch (b2Err) {
          console.error('[Backblaze B2] Upload warning (falling back to KV):', b2Err);
        }
      }

      // 2. Store binary file in edge KV cache (deduplicates identical capes across all players)
      await kv.put(`file:${fileHash}`, bodyBuffer);

      // 2.5 Track user capes (up to 30)
      if (!userCapes.includes(fileHash)) {
        userCapes.push(fileHash);
        await kv.put(`user_capes:${uuid}`, JSON.stringify(userCapes));
      }

      // 3. Store player mapping in KV
      await kv.put(
        `player:${uuid}`,
        JSON.stringify({
          hash: fileHash,
          playerName: playerName,
          fileName: fileName,
          updatedAt: Date.now(),
        })
      );

      return new Response(
        JSON.stringify({ success: true, uuid: uuid, hash: fileHash }),
        { headers: corsHeaders }
      );
    }

    // 6. DELETE /capes/:uuid (Un-equip cape)
    if (path.startsWith('/capes/') && method === 'DELETE') {
      const uuid = path.replace('/capes/', '').toLowerCase();
      const currentCape = await kv.get(`player:${uuid}`, { type: 'json' });
      if (currentCape && currentCape.hash) {
        let userCapes = (await kv.get(`user_capes:${uuid}`, { type: 'json' })) || [];
        userCapes = userCapes.filter(h => h !== currentCape.hash);
        await kv.put(`user_capes:${uuid}`, JSON.stringify(userCapes));
      }
      await kv.delete(`player:${uuid}`);
      return new Response(JSON.stringify({ success: true }), { headers: corsHeaders });
    }

    return new Response('Not Found', { status: 404 });
  },
};
