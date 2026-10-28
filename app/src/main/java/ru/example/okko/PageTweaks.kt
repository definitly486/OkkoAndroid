package ru.example.okko

/** Правки внешнего вида страницы okko внутри WebView. */
internal object PageTweaks {

    /**
     * Скрывает плашку «Открыть в приложении», которую рисует сама страница.
     * Ищет текстовый узел с этой фразой, поднимается до fixed/sticky-контейнера
     * (сама плашка) и прячет его.
     *
     * Нагрузка сведена к минимуму: следим только за добавлением узлов (без characterData,
     * чтобы счётчики времени не вызывали проверок), проверяем только добавленные поддеревья,
     * не чаще раза в 1,5 с, и не работаем, пока включён режим «Только звук».
     */
    const val HIDE_OPEN_IN_APP = """
(function() {
  if (window.__okkoBannerKiller) return;
  window.__okkoBannerKiller = true;
  var re = /открыть\s+в\s+приложени/i;
  var DELAY = 1500;
  var timer = null;
  var pending = [];

  function hideFor(node) {
    var el = node.parentElement;
    var target = null;
    for (var cur = el, depth = 0; cur && cur !== document.body && depth < 8; cur = cur.parentElement, depth++) {
      var pos = '';
      try { pos = getComputedStyle(cur).position; } catch (_) {}
      if (pos === 'fixed' || pos === 'sticky') { target = cur; break; }
    }
    if (!target) target = (el && el.closest('a,button')) || el;
    if (target && target.style.display !== 'none') {
      target.style.setProperty('display', 'none', 'important');
    }
  }

  function checkText(n) {
    var v = n.nodeValue;
    if (v && v.length < 80 && re.test(v)) hideFor(n);
  }

  function scanNode(root) {
    if (!root || !root.isConnected) return;
    if (root.nodeType === 3) { checkText(root); return; }
    if (root.nodeType !== 1) return;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) checkText(n);
  }

  function flush() {
    timer = null;
    var nodes = pending;
    pending = [];
    // В режиме «Только звук» страницу никто не видит — не тратим процессор.
    if (window.__okkoBackgroundAudio) return;
    for (var i = 0; i < nodes.length; i++) scanNode(nodes[i]);
  }

  function schedule() { if (!timer) timer = setTimeout(flush, DELAY); }

  new MutationObserver(function(records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) pending.push(added[j]);
    }
    if (pending.length > 200) pending = [document.body || document.documentElement];
    schedule();
  }).observe(document.documentElement, {childList: true, subtree: true});

  pending.push(document.documentElement);
  schedule();
})()
"""
}
