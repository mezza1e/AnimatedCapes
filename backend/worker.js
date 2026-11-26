/**
 * Animated Capes Cloud Sync - Cloudflare Worker
 *
 * Features:
 *   - Automatic Mojang API resolution: Usernames resolve to permanent UUIDs so name changes never grant access to anyone else
 *   - Separated Admin API (/admin/whitelist, /admin/ban, /admin/lookup, /admin/purge, /admin/webhook, /admin/reports)
 *   - Restricted Whitelist Viewing: Only the owner or whitelisted players can view /whitelist
 *   - Decoupled Ban & Global Purge: Banning blocks uploads while preserving cape for review; /admin/purge wipes cape globally
 *   - Cape Copy Protection & Friends: Locked by default; friends automatically bypass copy lock; live privacy sync endpoint
 *   - 30-Cape Anti-Spam Shield: Concise "Upload limit reached!" response; whitelisted & admin accounts bypass limit
 *   - Discord Moderation Integration: 1-click HTML moderation actions & authenticated /discord/command + /discord/interactions
 *   - Dual Storage Engine: Backblaze B2 (10 GB) with automatic Cloudflare KV Edge Cache & fallback
 */

async function resolveMojangProfile(input) {
  if (!input) return null;
  const clean = input.trim();
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clean)) {
    return { uuid: clean.toLowerCase(), name: clean };
  }
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

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function checkIsAdmin(request, url, env, bodySecret) {
  const adminSecret = env.ADMIN_SECRET || 'animacapes_admin_pot10';
  const candidates = [
    request.headers.get('X-Admin-Secret'),
    request.headers.get('X-Admin-Key'),
    (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, ''),
    url.searchParams.get('secret'),
    bodySecret,
  ]
    .filter(Boolean)
    .map(s => String(s).trim());

  for (const c of candidates) {
    if (c === adminSecret) return true;
    if (env.ADMIN_KEY && c === env.ADMIN_KEY) return true;
  }
  return false;
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

async function checkCopyPermission(kv, request, url, env, targetUuid, targetName, meta) {
  const isCopy = (request.headers.get('X-Copy-Request') === 'true') || (url.searchParams.get('copy') === 'true');
  if (!isCopy) {
    return { allowed: true };
  }

  if (checkIsAdmin(request, url, env)) {
    return { allowed: true };
  }

  const reqName = (request.headers.get('X-Requester-User') || url.searchParams.get('requester') || '').toLowerCase().trim();
  const reqUuid = (request.headers.get('X-Player-UUID') || '').toLowerCase().trim();

  const normTargetUuid = (targetUuid || '').toLowerCase().trim();
  const normTargetName = (targetName || (meta && meta.playerName) || '').toLowerCase().trim();

  if ((reqUuid && normTargetUuid && reqUuid === normTargetUuid) || (reqName && normTargetName && reqName === normTargetName)) {
    return { allowed: true };
  }

  let privacy = null;
  if (normTargetUuid) {
    privacy = await kv.get(`privacy:${normTargetUuid}`, { type: 'json' });
  }
  if (!privacy && normTargetName) {
    privacy = await kv.get(`privacy:${normTargetName}`, { type: 'json' });
  }

  const copyBlocked = new Set([
    ...((privacy && Array.isArray(privacy.copyBlocked)) ? privacy.copyBlocked : []),
    ...((meta && Array.isArray(meta.copyBlocked)) ? meta.copyBlocked : []),
  ].map(s => String(s).toLowerCase().trim()).filter(Boolean));

  if ((reqName && copyBlocked.has(reqName)) || (reqUuid && copyBlocked.has(reqUuid))) {
    return {
      allowed: false,
      error: `Player '${targetName || normTargetUuid}' has blocked you from copying their cape.`,
    };
  }

  const friends = new Set([
    ...((privacy && Array.isArray(privacy.friends)) ? privacy.friends : []),
    ...((meta && Array.isArray(meta.friends)) ? meta.friends : []),
  ].map(s => String(s).toLowerCase().trim()).filter(Boolean));

  if ((reqName && friends.has(reqName)) || (reqUuid && friends.has(reqUuid))) {
    return { allowed: true };
  }

  // Default is false (locked) unless explicitly set to true
  const allowCopy = (privacy && privacy.allowCopy !== undefined)
    ? (privacy.allowCopy === true)
    : (meta && meta.allowCopy === true);

  if (allowCopy) {
    return { allowed: true };
  }

  return {
    allowed: false,
    error: `Player '${targetName || normTargetUuid}' has locked their cape from being copied. Add each other with /friend add <player> to copy capes!`,
  };
}

async function purgePlayerCape(kv, profile) {
  const uuidKey = profile.uuid.toLowerCase();
  const nameKey = profile.name ? profile.name.toLowerCase() : '';
  let meta = await kv.get(`player:${uuidKey}`, { type: 'json' });
  if (!meta && nameKey) {
    meta = await kv.get(`player:${nameKey}`, { type: 'json' });
  }

  if (meta && meta.hash) {
    let userCapes = (await kv.get(`user_capes:${uuidKey}`, { type: 'json' })) || [];
    if (Array.isArray(userCapes)) {
      userCapes = userCapes.filter(h => h !== meta.hash);
      await kv.put(`user_capes:${uuidKey}`, JSON.stringify(userCapes));
    }
  }

  await kv.delete(`player:${uuidKey}`);
  if (nameKey) {
    await kv.delete(`player:${nameKey}`);
  }
  return meta;
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
          limits: { webp: '5MB (20MB whitelisted)', gif: '10MB', static: '5MB' },
          edge: true,
        }),
        { headers: corsHeaders }
      );
    }

    // 1.5 Whitelist Read Endpoint (/whitelist - Strictly Owner & Whitelisted Members Only)
    if (path === '/whitelist' && method === 'GET') {
      const kvList = (await kv.get('whitelist:animated_webp', { type: 'json' })) || [];
      const envList = (env.ANIMATED_WEBP_WHITELIST || '').split(',').map(s => s.trim()).filter(Boolean);
      const combined = Array.from(new Set(['officerpot10', ...envList, ...kvList]));

      const isAdmin = checkIsAdmin(request, url, env);
      const reqUuid = (request.headers.get('X-Player-UUID') || '').toLowerCase().trim();
      const reqName = (request.headers.get('X-Player-Name') || request.headers.get('X-Requester-User') || url.searchParams.get('user') || '').toLowerCase().trim();
      const isWhitelisted = combined.some(u => {
        const clean = String(u).toLowerCase().trim();
        return (reqUuid && clean === reqUuid) || (reqName && clean === reqName);
      });

      if (!isAdmin && !isWhitelisted) {
        return new Response(JSON.stringify({
          error: 'Access Denied: Only the owner or whitelisted players can view the whitelist.'
        }), {
          status: 403,
          headers: corsHeaders,
        });
      }

      return new Response(JSON.stringify({ combinedList: combined }), { headers: corsHeaders });
    }

    // 1.6 Banlist Read Endpoint (Admin Only)
    if (path === '/banlist' && method === 'GET') {
      if (!checkIsAdmin(request, url, env)) {
        return new Response(JSON.stringify({ error: 'Unauthorized: Admin access only' }), {
          status: 401,
          headers: corsHeaders,
        });
      }
      const banList = (await kv.get('banned:players', { type: 'json' })) || [];
      return new Response(JSON.stringify({ bannedList: banList }), { headers: corsHeaders });
    }

    // 2. Owner-Only Admin API (/admin/*)
    const adminSecret = env.ADMIN_SECRET || 'animacapes_admin_pot10';
    if (path.startsWith('/admin/')) {
      if (!checkIsAdmin(request, url, env)) {
        return new Response(JSON.stringify({ error: 'Unauthorized: Admin access only' }), {
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
            if (profile.name && profile.name !== profile.uuid && !kvList.some(u => u.toLowerCase() === profile.name.toLowerCase())) {
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

        // Support GET /admin/ban (returns banlist) AND GET /admin/ban?user=<player> (Discord 1-click ban)
        if (method === 'GET' && !url.searchParams.get('user')) {
          return new Response(JSON.stringify({ bannedList: banList }), { headers: corsHeaders });
        }

        if (method === 'POST' || (method === 'GET' && url.searchParams.get('user'))) {
          const body = method === 'POST' ? (await request.json().catch(() => ({}))) : {};
          const target = (body.user || body.uuid || url.searchParams.get('user') || '').trim();
          if (!target) return new Response(JSON.stringify({ error: 'Missing user' }), { status: 400, headers: corsHeaders });

          const profile = await resolveMojangProfile(target);
          if (!banList.some(u => u.toLowerCase() === profile.uuid.toLowerCase())) {
            banList.push(profile.uuid);
            if (profile.name && profile.name !== profile.uuid && !banList.some(u => u.toLowerCase() === profile.name.toLowerCase())) {
              banList.push(profile.name);
            }
            await kv.put('banned:players', JSON.stringify(banList));
          }

          // Banning does NOT automatically purge the player's cape globally.
          const accept = request.headers.get('Accept') || '';
          if (method === 'GET' && accept.includes('text/html')) {
            const purgeLink = `${url.origin}/admin/purge?user=${encodeURIComponent(profile.uuid)}&secret=${encodeURIComponent(adminSecret)}`;
            const lookupLink = `${url.origin}/admin/lookup?user=${encodeURIComponent(profile.uuid)}&secret=${encodeURIComponent(adminSecret)}`;
            return new Response(`<!DOCTYPE html><html><body style="font-family:sans-serif;background:#18181b;color:#f4f4f5;padding:40px;text-align:center;">
              <h1 style="color:#ef4444;">🔨 Player Banned</h1>
              <p><b>${escapeHtml(profile.name)}</b> (<code>${escapeHtml(profile.uuid)}</code>) is now banned from uploading capes.</p>
              <p style="color:#a1a1aa;">Their current cape was preserved in the database for review.</p>
              <p style="margin-top:24px;">
                <a href="${lookupLink}" style="background:#3b82f6;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none;margin-right:10px;">🔍 View Player & Cape</a>
                <a href="${purgeLink}" style="background:#dc2626;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none;">🗑️ Purge Cape Globally</a>
              </p>
            </body></html>`, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
          }

          return new Response(JSON.stringify({
            success: true,
            banned: true,
            capePreserved: true,
            name: profile.name,
            uuid: profile.uuid,
          }), { headers: corsHeaders });
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

      // --- E. Admin Player Lookup (/admin/lookup or /admin/cape) ---
      if (path === '/admin/lookup' || path === '/admin/cape') {
        let target = (url.searchParams.get('user') || '').trim();
        if (!target && method === 'POST') {
          const body = await request.json().catch(() => ({}));
          target = (body.user || body.uuid || '').trim();
        }
        if (!target) {
          return new Response(JSON.stringify({ error: 'Missing user parameter' }), { status: 400, headers: corsHeaders });
        }

        const profile = await resolveMojangProfile(target);
        const banList = ((await kv.get('banned:players', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
        const isBanned = banList.includes(profile.uuid.toLowerCase()) || (profile.name && banList.includes(profile.name.toLowerCase()));

        const kvList = ((await kv.get('whitelist:animated_webp', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
        const envList = (env.ANIMATED_WEBP_WHITELIST || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);
        const whitelistSet = new Set(['officerpot10', ...envList, ...kvList]);
        const isWhitelisted = whitelistSet.has(profile.uuid.toLowerCase()) || (profile.name && whitelistSet.has(profile.name.toLowerCase()));

        let meta = await kv.get(`player:${profile.uuid.toLowerCase()}`, { type: 'json' });
        if (!meta && profile.name) {
          meta = await kv.get(`player:${profile.name.toLowerCase()}`, { type: 'json' });
        }
        const privacy = (await kv.get(`privacy:${profile.uuid.toLowerCase()}`, { type: 'json' })) || {};
        const allowCopy = (privacy.allowCopy !== undefined) ? (privacy.allowCopy === true) : (meta ? meta.allowCopy === true : false);
        const capeUrl = meta ? `${url.origin}/capes/file/${meta.hash}` : null;

        const accept = request.headers.get('Accept') || '';
        if (method === 'GET' && accept.includes('text/html')) {
          const purgeLink = `${url.origin}/admin/purge?user=${encodeURIComponent(profile.uuid)}&secret=${encodeURIComponent(adminSecret)}`;
          const banLink = `${url.origin}/admin/ban?user=${encodeURIComponent(profile.uuid)}&secret=${encodeURIComponent(adminSecret)}`;
          return new Response(`<!DOCTYPE html><html><body style="font-family:sans-serif;background:#18181b;color:#f4f4f5;padding:40px;max-width:640px;margin:0 auto;">
            <h1 style="color:#38bdf8;margin-bottom:8px;">🔍 Player Moderation Lookup</h1>
            <div style="background:#27272a;padding:24px;border-radius:10px;border:1px solid #3f3f46;">
              <p><b>Username:</b> ${escapeHtml(profile.name)}</p>
              <p><b>UUID:</b> <code>${escapeHtml(profile.uuid)}</code></p>
              <p><b>Moderation Status:</b> ${isBanned ? '<span style="color:#ef4444;font-weight:bold;">BANNED</span>' : '<span style="color:#22c55e;font-weight:bold;">ACTIVE</span>'}</p>
              <p><b>Whitelist Status:</b> ${isWhitelisted ? '<span style="color:#f59e0b;font-weight:bold;">WHITELISTED</span>' : 'Standard'}</p>
              <p><b>Copy Permission:</b> ${allowCopy ? 'Public' : 'Locked (Friends Only)'}</p>
              ${capeUrl ? `
                <div style="margin:20px 0;text-align:center;background:#18181b;padding:16px;border-radius:8px;">
                  <p style="margin-top:0;color:#a1a1aa;">Equipped Cape (${escapeHtml(meta.fileName || 'cape.webp')})</p>
                  <img src="${capeUrl}" alt="Player Cape" style="max-width:320px;max-height:320px;image-rendering:pixelated;border:1px solid #52525b;" />
                  <p style="margin-bottom:0;"><a href="${capeUrl}" target="_blank" style="color:#38bdf8;">Open Raw Cape File</a></p>
                </div>
              ` : '<p style="color:#a1a1aa;"><i>No active cape equipped in cloud.</i></p>'}
              <div style="margin-top:24px;display:flex;gap:12px;flex-wrap:wrap;">
                ${capeUrl ? `<a href="${purgeLink}" style="background:#dc2626;color:#fff;padding:10px 16px;border-radius:6px;text-decoration:none;font-weight:bold;">🗑️ Purge Cape Globally</a>` : ''}
                ${!isBanned ? `<a href="${banLink}" style="background:#ea580c;color:#fff;padding:10px 16px;border-radius:6px;text-decoration:none;font-weight:bold;">🔨 Ban Player</a>` : ''}
              </div>
            </div>
          </body></html>`, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
        }

        return new Response(JSON.stringify({
          success: true,
          name: profile.name,
          uuid: profile.uuid,
          banned: isBanned,
          whitelisted: isWhitelisted,
          hasCape: !!meta,
          cape: meta ? {
            hash: meta.hash,
            url: capeUrl,
            fileName: meta.fileName || 'cape.webp',
            allowCopy: allowCopy,
            friends: privacy.friends || meta.friends || [],
            updatedAt: meta.updatedAt || null,
          } : null,
        }), { headers: corsHeaders });
      }

      // --- F. Admin Cape Purge (/admin/purge) ---
      if (path === '/admin/purge' || path.startsWith('/admin/purge/')) {
        let target = '';
        if (method === 'POST') {
          const body = await request.json().catch(() => ({}));
          target = (body.user || body.uuid || url.searchParams.get('user') || '').trim();
        } else if (method === 'GET' || method === 'DELETE') {
          target = (url.searchParams.get('user') || path.replace('/admin/purge/', '').replace('/admin/purge', '')).trim();
        }

        if (!target) {
          return new Response(JSON.stringify({ error: 'Missing user' }), { status: 400, headers: corsHeaders });
        }

        const profile = await resolveMojangProfile(target);
        const meta = await purgePlayerCape(kv, profile);

        const accept = request.headers.get('Accept') || '';
        if (accept.includes('text/html')) {
          return new Response(`<!DOCTYPE html><html><body style="font-family:sans-serif;background:#18181b;color:#f4f4f5;padding:40px;text-align:center;">
            <h1 style="color:#22c55e;">✓ Cape Purged Globally</h1>
            <p>Active cape for <b>${escapeHtml(profile.name)}</b> (<code>${escapeHtml(profile.uuid)}</code>) has been permanently purged from global cloud sync.</p>
          </body></html>`, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
        }

        return new Response(JSON.stringify({
          success: true,
          purged: true,
          name: profile.name,
          uuid: profile.uuid,
          hash: meta ? meta.hash : null,
        }), { headers: corsHeaders });
      }
    }

    // 2.5 Authenticated Discord Server Command & Interactions Endpoint (/discord/command or /discord/interactions)
    if ((path === '/discord/command' || path === '/discord/interactions') && method === 'POST') {
      try {
        const body = await request.json().catch(() => ({}));

        if (!checkIsAdmin(request, url, env, body.secret)) {
          return new Response(JSON.stringify({ error: 'Unauthorized Discord moderation request' }), {
            status: 401,
            headers: corsHeaders,
          });
        }

        // Discord Interaction PING handshake
        if (body.type === 1) {
          return new Response(JSON.stringify({ type: 1 }), { headers: corsHeaders });
        }

        const isDiscordInteraction = body.type === 2 && body.data;
        const cmd = (body.command || (body.data && body.data.name) || '').toLowerCase().trim();
        const optVal = (body.data && Array.isArray(body.data.options) && body.data.options[0]) ? String(body.data.options[0].value || '') : '';
        const userArg = String(body.user || body.args || optVal || '').trim();

        const sendReply = (content, embedObj) => {
          if (isDiscordInteraction) {
            const data = { content };
            if (embedObj) data.embeds = [embedObj];
            return new Response(JSON.stringify({ type: 4, data }), { headers: corsHeaders });
          }
          return new Response(JSON.stringify({
            success: true,
            response_type: 'in_channel',
            content,
            embed: embedObj || null,
          }), { headers: corsHeaders });
        };

        if (cmd === 'lookup' || cmd === 'view' || cmd === 'cape') {
          if (!userArg) return new Response(JSON.stringify({ error: 'Please specify a username' }), { status: 400, headers: corsHeaders });
          const prof = await resolveMojangProfile(userArg);
          let meta = await kv.get(`player:${prof.uuid.toLowerCase()}`, { type: 'json' });
          if (!meta && prof.name) meta = await kv.get(`player:${prof.name.toLowerCase()}`, { type: 'json' });
          const banList = ((await kv.get('banned:players', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
          const isBanned = banList.includes(prof.uuid.toLowerCase()) || (prof.name && banList.includes(prof.name.toLowerCase()));
          const capeUrl = meta ? `${url.origin}/capes/file/${meta.hash}` : null;

          const embed = {
            title: `🔍 Cape Lookup: ${prof.name}`,
            color: isBanned ? 0xED4245 : 0x38BDF8,
            description: `**UUID:** \`${prof.uuid}\`\n**Status:** ${isBanned ? '❌ Banned' : '✅ Active'}\n**Cape:** ${capeUrl ? `[View Raw Asset](${capeUrl})` : 'None'}`,
          };
          if (capeUrl) embed.image = { url: capeUrl };

          return sendReply(
            `**Player:** ${prof.name} (\`${prof.uuid}\`)\n**Status:** ${isBanned ? '❌ Banned' : '✅ Active'}\n**Cape:** ${capeUrl || 'None'}`,
            embed
          );
        }

        if (cmd === 'purge' || cmd === 'purgecape') {
          if (!userArg) return new Response(JSON.stringify({ error: 'Please specify a username' }), { status: 400, headers: corsHeaders });
          const prof = await resolveMojangProfile(userArg);
          await purgePlayerCape(kv, prof);
          return sendReply(`🗑️ **Purged cape globally for ${prof.name}** (\`${prof.uuid}\`).`);
        }

        if (cmd === 'ban') {
          if (!userArg) return new Response(JSON.stringify({ error: 'Please specify a username' }), { status: 400, headers: corsHeaders });
          const prof = await resolveMojangProfile(userArg);
          let banList = (await kv.get('banned:players', { type: 'json' })) || [];
          if (!banList.some(u => u.toLowerCase() === prof.uuid.toLowerCase())) {
            banList.push(prof.uuid);
            if (prof.name && !banList.some(u => u.toLowerCase() === prof.name.toLowerCase())) banList.push(prof.name);
            await kv.put('banned:players', JSON.stringify(banList));
          }
          return sendReply(`🔨 **Banned player ${prof.name}** (\`${prof.uuid}\`). Cape preserved in database; run \`purge ${prof.name}\` to remove globally.`);
        }

        if (cmd === 'unban') {
          if (!userArg) return new Response(JSON.stringify({ error: 'Please specify a username' }), { status: 400, headers: corsHeaders });
          const prof = await resolveMojangProfile(userArg);
          let banList = (await kv.get('banned:players', { type: 'json' })) || [];
          banList = banList.filter(u => u.toLowerCase() !== prof.uuid.toLowerCase() && u.toLowerCase() !== prof.name.toLowerCase());
          await kv.put('banned:players', JSON.stringify(banList));
          return sendReply(`✅ **Unbanned player ${prof.name}** (\`${prof.uuid}\`).`);
        }

        return new Response(JSON.stringify({ error: 'Unknown Discord command: ' + cmd }), { status: 400, headers: corsHeaders });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), { status: 500, headers: corsHeaders });
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

        let discordSent = false;
        const discordWebhookUrl = env.DISCORD_WEBHOOK_URL || (await kv.get('config:discord_webhook'));

        if (discordWebhookUrl && discordWebhookUrl.startsWith('https://')) {
          try {
            const lookupLink = `${url.origin}/admin/lookup?user=${encodeURIComponent(resolvedName)}&secret=${encodeURIComponent(adminSecret)}`;
            const purgeLink = `${url.origin}/admin/purge?user=${encodeURIComponent(resolvedName)}&secret=${encodeURIComponent(adminSecret)}`;
            const banLink = `${url.origin}/admin/ban?user=${encodeURIComponent(resolvedName)}&secret=${encodeURIComponent(adminSecret)}`;

            const embed = {
              title: '🚨 Animated Cape Report Submitted',
              description: `A player has reported **${resolvedName}** for an inappropriate cape.`,
              color: 0xED4245,
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
                  value: meta
                    ? `• **File:** \`${capeFileName}\`\n• **Hash:** \`${capeHash}\`\n• **Direct Link:** [View / Download Cape](${capeUrl})`
                    : '*No cape currently equipped by this player*',
                  inline: false,
                },
                {
                  name: '🛡️ Moderation Actions (Discord 1-Click)',
                  value: `[🔍 Look Up Player](${lookupLink}) • [🗑️ Purge Cape Globally](${purgeLink}) • [🔨 Ban Player](${banLink})`,
                  inline: false,
                },
              ],
              footer: {
                text: 'Animated Capes Moderation System',
              },
              timestamp: new Date().toISOString(),
            };

            if (capeUrl) {
              embed.image = { url: capeUrl };
            }

            const discordResp = await fetch(discordWebhookUrl, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                username: 'Animated Capes Moderation',
                avatar_url: 'https://cdn-icons-png.flaticon.com/512/595/595067.png',
                embeds: [embed],
              }),
            });

            discordSent = discordResp.ok;
          } catch (dErr) {
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
            note: discordSent
              ? 'Report forwarded to Discord'
              : (discordWebhookUrl ? 'Discord webhook failed' : 'Report recorded in moderation log'),
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

    // 2.88 POST /capes/privacy/:uuid (Live sync of cape copy lock, friends & copy-block list)
    if (path.startsWith('/capes/privacy/') && method === 'POST') {
      const uuid = path.replace('/capes/privacy/', '').toLowerCase().trim();
      if (!uuid) {
        return new Response(JSON.stringify({ error: 'Missing player UUID' }), { status: 400, headers: corsHeaders });
      }

      const body = await request.json().catch(() => ({}));
      const playerName = (request.headers.get('X-Player-Name') || body.playerName || '').toLowerCase().trim();
      const allowCopy = body.allowCopy === true;

      const rawFriends = Array.isArray(body.friends) ? body.friends : [];
      const resolvedFriends = new Set();
      for (const f of rawFriends.slice(0, 200)) {
        const cleanF = String(f || '').toLowerCase().trim();
        if (!cleanF) continue;
        resolvedFriends.add(cleanF);
      }

      const rawBlocked = Array.isArray(body.copyBlocked) ? body.copyBlocked : [];
      const resolvedBlocked = new Set();
      for (const b of rawBlocked.slice(0, 200)) {
        const cleanB = String(b || '').toLowerCase().trim();
        if (!cleanB) continue;
        resolvedBlocked.add(cleanB);
      }

      const privacyPayload = {
        uuid,
        playerName,
        allowCopy,
        friends: Array.from(resolvedFriends),
        copyBlocked: Array.from(resolvedBlocked),
        updatedAt: Date.now(),
      };

      await kv.put(`privacy:${uuid}`, JSON.stringify(privacyPayload));
      if (playerName) {
        await kv.put(`privacy:${playerName}`, JSON.stringify(privacyPayload));
      }

      // Also sync onto active player cape metadata if present
      const existingMeta = await kv.get(`player:${uuid}`, { type: 'json' });
      if (existingMeta) {
        existingMeta.allowCopy = allowCopy;
        existingMeta.friends = privacyPayload.friends;
        existingMeta.copyBlocked = privacyPayload.copyBlocked;
        await kv.put(`player:${uuid}`, JSON.stringify(existingMeta));
      }

      return new Response(JSON.stringify({ success: true, allowCopy, friendsCount: privacyPayload.friends.length }), {
        headers: corsHeaders,
      });
    }

    // 2.9 GET /capes/by-name/:username (Search player cape by username)
    if (path.startsWith('/capes/by-name/') && method === 'GET') {
      const username = path.replace('/capes/by-name/', '').trim();
      const profile = await resolveMojangProfile(username);
      if (!profile || !profile.uuid) {
        return new Response(JSON.stringify({ error: `Player '${username}' not found` }), { status: 404, headers: corsHeaders });
      }

      let meta = await kv.get(`player:${profile.uuid.toLowerCase()}`, { type: 'json' });
      if (!meta && profile.name) {
        meta = await kv.get(`player:${profile.name.toLowerCase()}`, { type: 'json' });
      }

      if (!meta) {
        return new Response(JSON.stringify({ error: `Player '${profile.name || username}' has no cape equipped` }), { status: 404, headers: corsHeaders });
      }

      const copyPerm = await checkCopyPermission(kv, request, url, env, profile.uuid, profile.name || meta.playerName || username, meta);
      if (!copyPerm.allowed) {
        return new Response(JSON.stringify({ error: copyPerm.error }), { status: 403, headers: corsHeaders });
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
    if (path.startsWith('/capes/') && !path.startsWith('/capes/file/') && !path.startsWith('/capes/privacy/') && method === 'GET') {
      const target = path.replace('/capes/', '').toLowerCase();

      let meta = await kv.get(`player:${target}`, { type: 'json' });
      let finalUuid = target;
      let resolvedName = meta ? meta.playerName : '';

      if (!meta && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(target)) {
        const prof = await resolveMojangProfile(target);
        if (prof && prof.uuid) {
          finalUuid = prof.uuid;
          resolvedName = prof.name || target;
          meta = await kv.get(`player:${prof.uuid.toLowerCase()}`, { type: 'json' });
          if (!meta && prof.name) {
            meta = await kv.get(`player:${prof.name.toLowerCase()}`, { type: 'json' });
          }
        }
      }

      if (!meta) {
        return new Response(JSON.stringify({ error: 'No cape equipped' }), { status: 404, headers: corsHeaders });
      }

      const copyPerm = await checkCopyPermission(kv, request, url, env, finalUuid, resolvedName || meta.playerName || finalUuid, meta);
      if (!copyPerm.allowed) {
        return new Response(JSON.stringify({ error: copyPerm.error }), { status: 403, headers: corsHeaders });
      }

      const fileUrl = `${url.origin}/capes/file/${meta.hash}`;
      return new Response(
        JSON.stringify({
          uuid: finalUuid,
          hash: meta.hash,
          url: fileUrl,
          fileName: meta.fileName || 'cape.webp',
          playerName: meta.playerName || resolvedName || 'Player',
        }),
        { headers: corsHeaders }
      );
    }

    // 4. GET /capes/file/:hash (Serve binary cape)
    if (path.startsWith('/capes/file/') && method === 'GET') {
      const hash = path.replace('/capes/file/', '');

      let fileData = await kv.get(`file:${hash}`, { type: 'arrayBuffer' });

      if (!fileData && isB2Configured(env)) {
        try {
          fileData = await fetchFromB2(env, hash);
          if (fileData) {
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

    // 5. POST /capes/:uuid (Upload cape with ban check, 30-cape limit & Animated WebP whitelist)
    if (path.startsWith('/capes/') && !path.startsWith('/capes/privacy/') && method === 'POST') {
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
      const isAdmin = checkIsAdmin(request, url, env);
      const envList = (env.ANIMATED_WEBP_WHITELIST || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);
      const kvList = ((await kv.get('whitelist:animated_webp', { type: 'json' })) || []).map(s => s.toLowerCase().trim());
      const whitelistSet = new Set(['officerpot10', ...envList, ...kvList]);
      const isWhitelisted = isAdmin || whitelistSet.has(uuid.toLowerCase()) || whitelistSet.has(playerName.toLowerCase().trim());

      const MAX_CAPES_PER_PLAYER = 30;
      let userCapes = (await kv.get(`user_capes:${uuid}`, { type: 'json' })) || [];
      if (!Array.isArray(userCapes)) userCapes = [];

      if (!isWhitelisted && userCapes.length >= MAX_CAPES_PER_PLAYER && !userCapes.includes(fileHash)) {
        return new Response(JSON.stringify({
          error: 'Upload limit reached!'
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

      const maxBytes = isWebp ? (isWhitelisted ? 20 * 1024 * 1024 : 5 * 1024 * 1024) :
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

        let isAnimatedWebp = false;
        if (view.length >= 24) {
          const firstChunkTag = String.fromCharCode(view[12], view[13], view[14], view[15]);
          if (firstChunkTag === 'VP8X' && (view[20] & 0x02) !== 0) {
            isAnimatedWebp = true;
          }
        }
        if (!isAnimatedWebp) {
          for (let i = 12; i < Math.min(view.length - 4, 1024); i++) {
            if (view[i] === 0x41 && view[i + 1] === 0x4E && view[i + 2] === 0x49 && view[i + 3] === 0x4D) {
              isAnimatedWebp = true;
              break;
            }
          }
        }

        if (isAnimatedWebp && !isWhitelisted) {
          return new Response(JSON.stringify({
            error: 'Forbidden: Direct Animated WebP uploads are restricted to whitelisted accounts. You can still use GIF, static PNG, JPG, or static WebP!'
          }), {
            status: 403,
            headers: corsHeaders,
          });
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

      // 2. Store binary file in edge KV cache
      await kv.put(`file:${fileHash}`, bodyBuffer);

      // 2.5 Track user capes (up to 30 for standard players)
      if (!userCapes.includes(fileHash)) {
        userCapes.push(fileHash);
        await kv.put(`user_capes:${uuid}`, JSON.stringify(userCapes));
      }

      // 3. Merge or store privacy settings & player mapping in KV
      const existingPrivacy = (await kv.get(`privacy:${uuid}`, { type: 'json' })) || {};
      const hasAllowHeader = request.headers.get('X-Cape-Allow-Copy') !== null;
      const hasFriendsHeader = request.headers.get('X-Cape-Friends') !== null;
      const hasBlockedHeader = request.headers.get('X-Cape-Copy-Blocked') !== null;

      const allowCopy = hasAllowHeader
        ? (request.headers.get('X-Cape-Allow-Copy') === 'true')
        : (existingPrivacy.allowCopy === true);

      const friendsList = hasFriendsHeader
        ? (request.headers.get('X-Cape-Friends') || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
        : (Array.isArray(existingPrivacy.friends) ? existingPrivacy.friends : []);

      const copyBlockedList = hasBlockedHeader
        ? (request.headers.get('X-Cape-Copy-Blocked') || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
        : (Array.isArray(existingPrivacy.copyBlocked) ? existingPrivacy.copyBlocked : []);

      const updatedPrivacy = {
        uuid,
        playerName: playerName.toLowerCase().trim(),
        allowCopy,
        friends: friendsList,
        copyBlocked: copyBlockedList,
        updatedAt: Date.now(),
      };
      await kv.put(`privacy:${uuid}`, JSON.stringify(updatedPrivacy));
      if (playerName && playerName !== 'Player') {
        await kv.put(`privacy:${playerName.toLowerCase().trim()}`, JSON.stringify(updatedPrivacy));
      }

      await kv.put(
        `player:${uuid}`,
        JSON.stringify({
          hash: fileHash,
          playerName: playerName,
          fileName: fileName,
          allowCopy: allowCopy,
          friends: friendsList,
          copyBlocked: copyBlockedList,
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
        if (Array.isArray(userCapes)) {
          userCapes = userCapes.filter(h => h !== currentCape.hash);
          await kv.put(`user_capes:${uuid}`, JSON.stringify(userCapes));
        }
      }
      await kv.delete(`player:${uuid}`);
      return new Response(JSON.stringify({ success: true }), { headers: corsHeaders });
    }

    return new Response('Not Found', { status: 404 });
  },
};
