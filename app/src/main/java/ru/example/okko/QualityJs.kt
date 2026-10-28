package ru.example.okko

/**
 * Эвристическое переключение качества в веб-плеере okko: открывает меню настроек плеера,
 * заходит в «Качество» (если оно вложенное) и выбирает самое низкое разрешение
 * (mode = 'low') либо «Авто» (mode = 'auto'). Вёрстка okko заранее неизвестна, поэтому
 * элементы ищутся по подписям/тексту. Результат — сообщение в консоль «OKKO_QUALITY:...».
 */
internal object QualityJs {

    private const val BODY = """
function(mode) {
  var RES = /(^|\D)(144|240|360|480|540|576|720|1080|1440|2160)p?(\D|${'$'})/i;
  var done = false;
  function say(m) { if (done) return; done = true; console.log('OKKO_QUALITY:' + m); }
  function txt(el) { return ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim(); }
  function vis(el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }

  function click(el) {
    var t = el;
    for (var i = 0; i < 4 && t; i++, t = t.parentElement) {
      var tag = (t.tagName || '').toLowerCase();
      if (tag === 'button' || tag === 'li' || t.getAttribute('role')) { el = t; break; }
    }
    try {
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(n) {
        var C = (n.indexOf('pointer') === 0 && window.PointerEvent) ? PointerEvent : MouseEvent;
        el.dispatchEvent(new C(n, {bubbles: true, cancelable: true, view: window}));
      });
    } catch (_) {}
    el.click();
  }

  function wake() {
    var v = document.querySelector('video');
    var t = (v && v.parentElement) || document.body;
    ['pointermove', 'mousemove', 'mouseover'].forEach(function(n) {
      try { t.dispatchEvent(new MouseEvent(n, {bubbles: true, view: window, clientX: 50, clientY: 50})); } catch (_) {}
    });
  }

  function findSettings() {
    var list = document.querySelectorAll(
      'button,[role=button],[aria-label],[title],[class*=setting],[class*=quality],[class*=gear]');
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      var cls = (typeof e.className === 'string') ? e.className : '';
      var label = (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' +
        cls + ' ' + txt(e).slice(0, 30);
      if (/качеств|quality|настройк|setting|gear/i.test(label) && vis(e) && !e.closest('a[href]')) return e;
    }
    return null;
  }

  // Самые «глубокие» видимые элементы с коротким текстом, подходящим под re.
  function leafs(re) {
    var out = [];
    var all = document.querySelectorAll('li,button,span,div,p,[role=menuitem],[role=option],[role=radio]');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      var t = txt(e);
      if (!t || t.length > 30 || !re.test(t) || !vis(e) || e.closest('a[href]')) continue;
      var deeper = false;
      for (var c = e.firstElementChild; c; c = c.nextElementSibling) {
        var ct = txt(c);
        if (ct && re.test(ct)) { deeper = true; break; }
      }
      if (!deeper) out.push({el: e, text: t});
    }
    return out;
  }

  function resNum(t) { var m = RES.exec(t); return m ? parseInt(m[2], 10) : 0; }

  function options() {
    if (mode === 'auto') return leafs(/^(авто|auto)/i);
    var r = leafs(RES).filter(function(o) { return resNum(o.text) > 0; });
    if (r.length) return r;
    return leafs(/низк|^low|^sd\b/i);
  }

  function pick(opts) {
    var best = opts[0];
    if (mode !== 'auto') {
      for (var i = 1; i < opts.length; i++) {
        var a = resNum(opts[i].text), b = resNum(best.text);
        if (a > 0 && (b === 0 || a < b)) best = opts[i];
      }
    }
    click(best.el);
    say('ok ' + (mode === 'auto' ? 'auto' : 'low ' + best.text));
    // Если меню не закрылось само — закрываем Escape.
    setTimeout(function() {
      if (options().length) {
        ['keydown', 'keyup'].forEach(function(n) {
          document.dispatchEvent(new KeyboardEvent(n, {key: 'Escape', keyCode: 27, bubbles: true}));
        });
      }
    }, 600);
  }

  wake();
  setTimeout(function() {
    var s = findSettings();
    if (!s) { say('fail no-settings'); return; }
    click(s);
    setTimeout(function() {
      var o = options();
      if (o.length) { pick(o); return; }
      var q = leafs(/качеств|quality|разрешен|resolution/i)[0];
      if (!q) { say('fail no-options'); return; }
      click(q.el);
      setTimeout(function() {
        var o2 = options();
        if (o2.length) pick(o2); else say('fail no-options');
      }, 700);
    }, 700);
  }, 300);
}
"""

    /** Выбрать самое низкое качество. */
    fun lowest(): String = "($BODY)('low')"

    /** Вернуть «Авто». */
    fun auto(): String = "($BODY)('auto')"
}
