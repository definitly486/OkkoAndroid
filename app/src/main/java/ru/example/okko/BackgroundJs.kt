package ru.example.okko

/** JS для режима «Только звук» в WebView. */
internal object BackgroundJs {

    /**
     * Скрывает от страницы факт сворачивания: document.hidden/visibilityState остаются
     * «видимыми», а visibilitychange/pagehide/freeze не доходят до обработчиков сайта
     * (многие плееры по ним ставят паузу). Если сайт всё же поставил паузу —
     * возобновляем то, что играло до этого.
     */
    const val ENABLE = """
(function() {
  window.__okkoBackgroundAudio = true;
  function realHidden() {
    try { return !!Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get.call(document); }
    catch (_) { return false; }
  }
  window.__okkoRealHidden = realHidden;

  if (!window.__okkoVisSpoof) {
    window.__okkoVisSpoof = true;
    var block = function(e) { if (window.__okkoBackgroundAudio) e.stopImmediatePropagation(); };
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function(n) {
      window.addEventListener(n, block, true);
      document.addEventListener(n, block, true);
    });
    function spoof(prop, fake) {
      try {
        var d = Object.getOwnPropertyDescriptor(Document.prototype, prop);
        if (!d || !d.get) return;
        Object.defineProperty(document, prop, {
          configurable: true,
          get: function() { return window.__okkoBackgroundAudio ? fake : d.get.call(document); }
        });
      } catch (_) {}
    }
    spoof('hidden', false);
    spoof('webkitHidden', false);
    spoof('visibilityState', 'visible');
    spoof('webkitVisibilityState', 'visible');
  }

  function resume(v) {
    if (!window.__okkoBackgroundAudio || !v.__okkoWasPlaying || v.ended) return;
    var p = v.play();
    if (p && p.catch) p.catch(function() {});
  }

  function attach(v) {
    if (v.__okkoBgAttached) return;
    v.__okkoBgAttached = true;
    v.__okkoWasPlaying = !v.paused && !v.ended;
    v.addEventListener('play', function() { v.__okkoWasPlaying = true; }, true);
    v.addEventListener('playing', function() { v.__okkoWasPlaying = true; }, true);
    v.addEventListener('ended', function() { v.__okkoWasPlaying = false; }, true);
    v.addEventListener('pause', function() {
      if (!v.__okkoWasPlaying || v.ended || !realHidden()) return;
      // Дадим сайту завершить свой обработчик паузы, затем продолжим.
      setTimeout(function() { if (v.paused) resume(v); }, 0);
    }, true);
    if (!v.hasAttribute('data-okko-original-style')) {
      v.setAttribute('data-okko-original-style', v.getAttribute('style') || '');
    }
    v.style.setProperty('opacity', '0.01', 'important');
  }

  document.querySelectorAll('video,audio').forEach(attach);
  if (!window.__okkoBgAudioObserver) {
    window.__okkoBgAudioObserver = new MutationObserver(function() {
      document.querySelectorAll('video,audio').forEach(attach);
    });
    window.__okkoBgAudioObserver.observe(document.documentElement, {childList: true, subtree: true});
  }
  // Для проверки по logcat: пока видео не декодируется, totalVideoFrames не растёт.
  if (window.__okkoFrameLog) clearInterval(window.__okkoFrameLog);
  window.__okkoFrameLog = setInterval(function() {
    document.querySelectorAll('video').forEach(function(v) {
      try {
        var q = v.getVideoPlaybackQuality();
        console.log('OKKO_BG_AUDIO:frames total=' + q.totalVideoFrames + ' dropped=' + q.droppedVideoFrames +
          ' paused=' + v.paused + ' t=' + Math.round(v.currentTime));
      } catch (_) {}
    });
  }, 5000);
  console.log('OKKO_BG_AUDIO:enabled url=' + location.href +
    ' mediaCount=' + document.querySelectorAll('video,audio').length);
  return true;
})()
"""

    const val DISABLE = """
(function() {
  window.__okkoBackgroundAudio = false;
  if (window.__okkoFrameLog) { clearInterval(window.__okkoFrameLog); window.__okkoFrameLog = null; }
  if (window.__okkoBgAudioObserver) {
    window.__okkoBgAudioObserver.disconnect();
    window.__okkoBgAudioObserver = null;
  }
  document.querySelectorAll('[data-okko-original-style]').forEach(function(v) {
    var s = v.getAttribute('data-okko-original-style');
    if (s) { v.setAttribute('style', s); } else { v.removeAttribute('style'); }
    v.removeAttribute('data-okko-original-style');
    v.__okkoBgAttached = false;
  });
  return true;
})()
"""
}
