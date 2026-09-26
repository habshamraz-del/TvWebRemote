/*
 * tvify.js — injected into every page by BrowserActivity.
 *
 * Two ways to use a site with the remote:
 *   1. TV menu:  every link, button and search box on the page, laid out as big tiles.
 *   2. Page view: the real page, with a highlight you move between clickable things.
 *
 * The Android side calls window.__tvify.enter(), .back() and .toggleMenu().
 * Arrow keys are handled here directly.
 */
(function () {
  'use strict';
  if (window.__tvify) return;

  var CFG = window.__tvifyConfig || {};

  var SELECTOR = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary',
    '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=option]',
    '[role=checkbox]', '[role=switch]', '[onclick]',
    '[contenteditable=""]', '[contenteditable=true]', '[tabindex]:not([tabindex="-1"])'
  ].join(',');

  var SECTIONS = ['Search and forms', 'Site menu', 'With pictures', 'Links', 'Page footer'];

  var host, root, ring, panel, titleEl, bodyEl, hint, hintTimer;
  var menuOpen = false, menuItems = [], menuTiles = [], menuIndex = 0, menuTouched = false;
  var current = null, ringQueued = false;

  /* ------------------------------------------------------------------ */
  /* Element helpers                                                     */
  /* ------------------------------------------------------------------ */

  function isRendered(el) { return el.getClientRects().length > 0; }

  function isVisible(el) {
    if (!el || !el.isConnected || !isRendered(el)) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && parseFloat(s.opacity) > 0.05;
  }

  function isField(el) {
    return !!el && el.nodeType === 1 && (el.matches('input, textarea, select') || el.isContentEditable);
  }

  function isTextField(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.tagName === 'TEXTAREA' || el.isContentEditable) return true;
    if (el.tagName !== 'INPUT') return false;
    return /^(text|search|email|url|tel|password|number|)$/.test((el.getAttribute('type') || '').toLowerCase());
  }

  /* Things a real Enter key press will activate once they have focus. */
  function enterActivates(el) {
    if (el.tagName === 'A' && el.hasAttribute('href')) return true;
    if (el.tagName === 'BUTTON' || el.tagName === 'SELECT' || el.tagName === 'SUMMARY') return true;
    return el.tagName === 'INPUT' && /^(submit|button|reset|image)$/i.test(el.type);
  }

  /* Drop wrappers that contain other clickable things; keep the innermost ones. */
  function innermost(list) {
    var set = new Set(list), wrappers = new Set();
    list.forEach(function (el) {
      for (var p = el.parentElement; p; p = p.parentElement) {
        if (set.has(p)) wrappers.add(p);
      }
    });
    return list.filter(function (el) { return !wrappers.has(el); });
  }

  function labelOf(el) {
    var t = el.getAttribute('aria-label') || '';
    if (!t && el.tagName !== 'INPUT' && el.tagName !== 'SELECT') t = el.innerText || el.textContent || '';
    if (!t && el.tagName === 'INPUT') t = el.value || el.getAttribute('placeholder') || '';
    if (!t) t = el.getAttribute('title') || el.getAttribute('placeholder') || '';
    if (!t && el.id) {
      var lab = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
      if (lab) t = lab.innerText;
    }
    if (!t && el.tagName === 'SELECT' && el.selectedOptions && el.selectedOptions[0]) t = el.selectedOptions[0].text;
    if (!t) {
      var img = el.querySelector && el.querySelector('img[alt]');
      if (img) t = img.alt;
    }
    t = (t || '').replace(/\s+/g, ' ').trim();
    return t.length > 90 ? t.slice(0, 87) + '…' : t;
  }

  /* Last-resort name for a link with no text: the end of its web address. */
  function addressLabel(el) {
    if (!el.href) return '';
    try {
      var u = new URL(el.href);
      var last = u.pathname.replace(/\/$/, '').split('/').pop() || u.hostname;
      return decodeURIComponent(last).replace(/\.[a-z0-9]+$/i, '').replace(/[-_]+/g, ' ');
    } catch (e) { return ''; }
  }

  function imageOf(el) {
    var img = el.querySelector && el.querySelector('img');
    if (!img) return '';
    var src = img.currentSrc || img.src || '';
    if (!src || src.indexOf('data:') === 0) return '';
    if (img.naturalWidth && img.naturalWidth < 48) return ''; // skip icons and tracking pixels
    return src;
  }

  function sectionOf(el, img) {
    if (isField(el)) return 'Search and forms';
    if (el.closest('nav, header, [role=navigation], [role=banner], [role=menubar], [role=menu], [role=tablist]')) return 'Site menu';
    if (el.closest('footer, [role=contentinfo]')) return 'Page footer';
    return img ? 'With pictures' : 'Links';
  }

  /* Visible clickable things, for moving around the real page. */
  function pageCandidates() {
    var all = document.querySelectorAll(SELECTOR), out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || el.getAttribute('aria-hidden') === 'true') continue;
      if (isVisible(el)) out.push(el);
    }
    return innermost(out);
  }

  /* Everything useful for the TV menu, including links hidden in collapsed site menus. */
  function menuCandidates() {
    var all = document.querySelectorAll(SELECTOR), out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled) continue;
      if (isVisible(el)) { out.push(el); continue; }
      if (el.tagName === 'A' && /^https?:/.test(el.href) &&
          el.closest('nav, header, [role=navigation], [role=menu]')) out.push(el);
    }
    out = innermost(out);

    var byKey = {}, items = [];
    out.forEach(function (el, n) {
      var label = labelOf(el), img = imageOf(el), weak = !label;
      if (weak) label = addressLabel(el);
      if (!label && !img) return;
      var href = el.tagName === 'A' ? el.getAttribute('href') : '';
      var key = href && href !== '#' && href.indexOf('javascript:') !== 0 ? 'a:' + el.href : 'x:' + n;
      var existing = byKey[key];
      if (existing) {
        // Same link twice (e.g. a picture and a title): merge into one tile.
        if (!existing.img && img) { existing.img = img; existing.section = sectionOf(existing.el, img); }
        if (existing.weak && !weak) { existing.label = label; existing.weak = false; }
        return;
      }
      var item = { el: el, label: label || 'Picture link', weak: weak, img: img, section: sectionOf(el, img) };
      byKey[key] = item;
      items.push(item);
    });
    return items.slice(0, 400);
  }

  /* ------------------------------------------------------------------ */
  /* Spatial navigation: find the nearest thing in the pressed direction */
  /* ------------------------------------------------------------------ */

  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1, b1) - Math.min(a2, b2)); }

  function pick(cands, from, dir) {
    var a = from.getBoundingClientRect();
    var acx = (a.left + a.right) / 2, acy = (a.top + a.bottom) / 2;
    var best = null, bestScore = Infinity;

    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el === from || el.contains(from) || from.contains(el)) continue;
      var b = el.getBoundingClientRect();
      if (b.width === 0 && b.height === 0) continue;
      var bcx = (b.left + b.right) / 2, bcy = (b.top + b.bottom) / 2;
      var main, cross, align;

      if (dir === 'down') {
        if (bcy <= acy + 1 || b.bottom <= a.bottom) continue;
        main = Math.max(0, b.top - a.bottom); cross = gap(a.left, a.right, b.left, b.right); align = Math.abs(bcx - acx);
      } else if (dir === 'up') {
        if (bcy >= acy - 1 || b.top >= a.top) continue;
        main = Math.max(0, a.top - b.bottom); cross = gap(a.left, a.right, b.left, b.right); align = Math.abs(bcx - acx);
      } else if (dir === 'right') {
        if (bcx <= acx + 1 || b.right <= a.right) continue;
        main = Math.max(0, b.left - a.right); cross = gap(a.top, a.bottom, b.top, b.bottom); align = Math.abs(bcy - acy);
      } else {
        if (bcx >= acx - 1 || b.left >= a.left) continue;
        main = Math.max(0, a.left - b.right); cross = gap(a.top, a.bottom, b.top, b.bottom); align = Math.abs(bcy - acy);
      }

      var score = main + cross * 3 + align * 0.05;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function firstInView(cands, dir) {
    var best = null, bestScore = Infinity;
    cands.forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) return;
      var score = dir === 'up' ? -(r.bottom * 1000) + r.left : r.top * 1000 + r.left;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    return best;
  }

  /* ------------------------------------------------------------------ */
  /* Overlay UI (in a shadow root so the website's CSS can't break it)   */
  /* ------------------------------------------------------------------ */

  var STYLE = `
    :host { all: initial; }
    * { box-sizing: border-box; }
    .ring {
      position: absolute; display: none; pointer-events: none;
      border: 4px solid #FFC24B; border-radius: 10px;
      box-shadow: 0 0 0 3px rgba(20,32,43,.7), 0 0 26px rgba(255,194,75,.75);
      transition: left .12s ease-out, top .12s ease-out, width .12s ease-out, height .12s ease-out;
    }
    .panel {
      position: absolute; inset: 0; overflow-y: auto; pointer-events: auto;
      background: #14202B; color: #EEF2F5;
      font: 20px/1.3 Roboto, "Noto Sans", system-ui, sans-serif;
      padding: 36px 56px 72px;
    }
    .panel[hidden] { display: none; }
    .head { display: flex; align-items: baseline; justify-content: space-between; gap: 32px; }
    .title { font-size: 32px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .keys { font-size: 16px; color: #9BB0C1; white-space: nowrap; }
    h2 { font-size: 20px; font-weight: 500; color: #9BB0C1; margin: 32px 0 14px; }
    .grid { display: grid; gap: 18px; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); }
    .grid.pictures { grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
    .tile {
      display: flex; flex-direction: column; overflow: hidden; cursor: pointer;
      background: #1E2E3C; border: 4px solid transparent; border-radius: 12px;
      min-height: 68px; transition: transform .12s ease-out, background .12s;
    }
    .tile img { width: 100%; aspect-ratio: 16 / 9; object-fit: cover; display: block; background: #0f1821; }
    .tile span {
      padding: 14px 16px; overflow: hidden;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
    }
    .tile.sel { border-color: #FFC24B; background: #28405A; transform: scale(1.05); }
    .empty { color: #9BB0C1; margin-top: 48px; font-size: 22px; }
    .hint {
      position: absolute; left: 50%; bottom: 36px; transform: translateX(-50%);
      background: rgba(20,32,43,.94); color: #EEF2F5; border: 2px solid #28405A;
      font: 18px Roboto, system-ui, sans-serif; padding: 12px 24px; border-radius: 999px;
      opacity: 0; transition: opacity .3s; pointer-events: none; white-space: nowrap;
    }
    .hint.show { opacity: 1; }
    @media (prefers-reduced-motion: reduce) { .ring, .tile, .hint { transition: none; } }
  `;

  function ensureHost() {
    if (host) {
      if (!host.isConnected) document.documentElement.appendChild(host);
      return;
    }
    host = document.createElement('tvify-overlay');
    host.style.cssText = 'all:initial;position:fixed;left:0;top:0;width:100vw;height:100vh;' +
      'z-index:2147483647;pointer-events:none;';
    root = host.attachShadow({ mode: 'open' });
    root.innerHTML =
      '<style>' + STYLE + '</style>' +
      '<div class="ring"></div>' +
      '<div class="panel" hidden>' +
      '  <div class="head"><div class="title"></div>' +
      '  <div class="keys">Arrows to move, OK to open, Back to see the full page</div></div>' +
      '  <div class="body"></div>' +
      '</div>' +
      '<div class="hint"></div>';
    ring = root.querySelector('.ring');
    panel = root.querySelector('.panel');
    titleEl = root.querySelector('.title');
    bodyEl = root.querySelector('.body');
    hint = root.querySelector('.hint');
    document.documentElement.appendChild(host);
  }

  function flashHint(text) {
    if (!hint) return;
    hint.textContent = text;
    hint.classList.add('show');
    clearTimeout(hintTimer);
    hintTimer = setTimeout(function () { hint.classList.remove('show'); }, 3500);
  }

  /* ------------------------------------------------------------------ */
  /* TV menu                                                             */
  /* ------------------------------------------------------------------ */

  function renderMenu() {
    ensureHost();
    var keep = menuItems[menuIndex] && menuItems[menuIndex].el;
    var items = menuCandidates();

    titleEl.textContent = document.title || location.hostname;
    bodyEl.textContent = '';
    menuTiles = [];
    var ordered = [], groups = {};
    items.forEach(function (it) { (groups[it.section] = groups[it.section] || []).push(it); });

    SECTIONS.forEach(function (name) {
      var list = groups[name];
      if (!list) return;
      var h = document.createElement('h2');
      h.textContent = name;
      bodyEl.appendChild(h);
      var grid = document.createElement('div');
      grid.className = name === 'With pictures' ? 'grid pictures' : 'grid';
      list.forEach(function (it) {
        var tile = document.createElement('div');
        tile.className = 'tile';
        if (it.img) {
          var im = document.createElement('img');
          im.src = it.img;
          im.loading = 'lazy';
          im.alt = '';
          tile.appendChild(im);
        }
        var span = document.createElement('span');
        span.textContent = (isTextField(it.el) ? '✎ ' : '') + it.label;
        tile.appendChild(span);
        var index = ordered.length;
        tile.addEventListener('click', function () { selectTile(index); activate(it.el); });
        grid.appendChild(tile);
        menuTiles.push(tile);
        ordered.push(it);
      });
      bodyEl.appendChild(grid);
    });

    menuItems = ordered;
    if (!ordered.length) {
      var empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = document.readyState === 'complete'
        ? 'Nothing clickable found on this page yet. Press Back to see the full page.'
        : 'Loading the page…';
      bodyEl.appendChild(empty);
      return;
    }
    var idx = 0;
    for (var i = 0; keep && i < ordered.length; i++) if (ordered[i].el === keep) { idx = i; break; }
    menuIndex = idx;
    selectTile(idx);
  }

  function selectTile(i) {
    if (!menuTiles.length) return;
    if (menuTiles[menuIndex]) menuTiles[menuIndex].classList.remove('sel');
    menuIndex = Math.max(0, Math.min(i, menuTiles.length - 1));
    var tile = menuTiles[menuIndex];
    tile.classList.add('sel');
    if (menuIndex === 0) panel.scrollTop = 0;
    else tile.scrollIntoView({ block: 'nearest' });
  }

  function moveMenu(dir) {
    if (!menuTiles.length) return;
    menuTouched = true;
    var next = pick(menuTiles, menuTiles[menuIndex], dir);
    if (next) selectTile(menuTiles.indexOf(next));
  }

  function openMenu() {
    ensureHost();
    menuOpen = true;
    menuTouched = false;
    menuIndex = 0;
    menuItems = [];
    renderMenu();
    panel.hidden = false;
    ring.style.display = 'none';
    return 'handled';
  }

  function closeMenu(quiet) {
    if (!menuOpen) return;
    menuOpen = false;
    panel.hidden = true;
    updateRing();
    if (!quiet) flashHint('Arrows move around the page. Hold OK for the TV menu.');
  }

  function toggleMenu() {
    if (menuOpen) closeMenu(false); else openMenu();
    return 'handled';
  }

  /* ------------------------------------------------------------------ */
  /* Page view: highlight ring that moves between real page elements     */
  /* ------------------------------------------------------------------ */

  function updateRing() {
    if (!ring) return;
    if (menuOpen || !current || !current.isConnected || !isRendered(current)) {
      ring.style.display = 'none';
      return;
    }
    var r = current.getBoundingClientRect(), pad = 4;
    ring.style.display = 'block';
    ring.style.left = (r.left - pad) + 'px';
    ring.style.top = (r.top - pad) + 'px';
    ring.style.width = (r.width + pad * 2) + 'px';
    ring.style.height = (r.height + pad * 2) + 'px';
  }

  function queueRing() {
    if (ringQueued) return;
    ringQueued = true;
    requestAnimationFrame(function () { ringQueued = false; updateRing(); });
  }

  function setCurrent(el) {
    current = el;
    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) { /* ignore */ }
    var r = el.getBoundingClientRect(), margin = 90;
    if (r.height < innerHeight - margin * 2) {
      if (r.top < margin) window.scrollBy(0, r.top - margin);
      else if (r.bottom > innerHeight - margin) window.scrollBy(0, r.bottom - innerHeight + margin);
    }
    updateRing();
  }

  function scrollerFor(el) {
    for (var p = el && el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      var oy = getComputedStyle(p).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight + 10) return p;
    }
    return null;
  }

  function scrollPage(dir) {
    var dy = (dir === 'down' ? 1 : -1) * Math.round(innerHeight * 0.7);
    var target = scrollerFor(current);
    (target || window).scrollBy({ top: dy, behavior: 'smooth' });
  }

  function movePage(dir) {
    var cands = pageCandidates();
    var cur = current;
    if (cur) {
      if (!isVisible(cur)) cur = null;
      else {
        var cr = cur.getBoundingClientRect();
        if (cr.bottom < 0 || cr.top > innerHeight) cur = null; // scrolled away from it
      }
    }
    if (!cur) {
      var first = firstInView(cands, dir);
      if (first) setCurrent(first);
      else if (dir === 'up' || dir === 'down') scrollPage(dir);
      return;
    }
    var next = pick(cands, cur, dir);
    if (next) {
      var r = next.getBoundingClientRect();
      var tooFar = (dir === 'down' && r.top > innerHeight * 1.5) || (dir === 'up' && r.bottom < -innerHeight * 0.5);
      if (!tooFar) { setCurrent(next); return; }
    }
    // Nothing nearby that way: scroll so long text between links can still be read.
    if (dir === 'up' || dir === 'down') scrollPage(dir);
  }

  /* ------------------------------------------------------------------ */
  /* Actions called from Android                                         */
  /* ------------------------------------------------------------------ */

  /* Returns 'native' when Android should send a real Enter key to the focused element,
     'input' when it should open the on-screen keyboard. */
  function activate(el) {
    if (menuOpen) closeMenu(true);
    var visible = isVisible(el);
    if (visible) setCurrent(el);

    if (isTextField(el)) {
      el.focus({ preventScroll: true });
      el.click();
      return 'input';
    }
    if (!visible && el.tagName === 'A' && /^https?:/.test(el.href)) {
      location.href = el.href; // link hidden in a collapsed menu
      return 'handled';
    }
    if (enterActivates(el)) {
      el.focus({ preventScroll: true });
      if (document.activeElement === el) return 'native';
    }
    el.click();
    return 'handled';
  }

  function enter() {
    if (menuOpen) {
      var it = menuItems[menuIndex];
      return it ? activate(it.el) : 'handled';
    }
    var ae = document.activeElement;
    if (isTextField(ae)) return ae.value ? 'native' : 'input'; // submit a filled box, else type
    if (current && current.isConnected) return activate(current);
    var first = firstInView(pageCandidates(), 'down');
    if (first) { setCurrent(first); return 'handled'; }
    return 'unhandled';
  }

  function back() {
    if (menuOpen) { closeMenu(false); return 'handled'; }
    var ae = document.activeElement;
    if (isField(ae)) { ae.blur(); return 'handled'; }
    return 'unhandled';
  }

  /* ------------------------------------------------------------------ */
  /* Wiring                                                              */
  /* ------------------------------------------------------------------ */

  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  var CODES = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };

  window.addEventListener('keydown', function (e) {
    var dir = DIRS[e.key] || CODES[e.keyCode];
    if (!dir || e.altKey || e.ctrlKey || e.metaKey) return;
    if (!menuOpen) {
      var ae = document.activeElement;
      if (isTextField(ae)) {
        if (dir === 'left' || dir === 'right') return; // move the text cursor
        ae.blur();
      }
    }
    e.preventDefault();
    e.stopImmediatePropagation();
    if (menuOpen) moveMenu(dir); else movePage(dir);
  }, true);

  window.addEventListener('scroll', queueRing, true);
  window.addEventListener('resize', queueRing);

  // Pages that load content late (or single-page apps): refresh the menu until the
  // viewer starts moving around, and keep the highlight in the right place.
  var mutationTimer = null;
  new MutationObserver(function () {
    clearTimeout(mutationTimer);
    mutationTimer = setTimeout(function () {
      ensureHost();
      if (menuOpen && !menuTouched) renderMenu();
      queueRing();
    }, 600);
  }).observe(document.documentElement, { childList: true, subtree: true });

  window.__tvify = {
    enter: enter,
    back: back,
    toggleMenu: toggleMenu,
    openMenu: openMenu,
    rescan: renderMenu
  };

  ensureHost();
  if (CFG.autoOpen) openMenu();
  else flashHint('Arrows move around the page. Hold OK for the TV menu.');
})();
