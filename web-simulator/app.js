/**
 * Wearable AI Reading Assistant — Visual Developer Console Logic
 * Replicates the pure Kotlin core-domain state machine, input policy,
 * image quality engine, and paragraph-aware segmented TTS in JavaScript.
 */

// State Enumeration
const States = {
  IDLE: 'IDLE',
  SCANNER_STARTING: 'SCANNER_STARTING',
  SCANNING: 'SCANNING',
  DOCUMENT_READY: 'DOCUMENT_READY',
  CAPTURING: 'CAPTURING',
  CAPTURED: 'CAPTURED',
  CAPTURE_VALIDATION: 'CAPTURE_VALIDATION',
  PROCESSING: 'PROCESSING',
  READY_TO_PLAY: 'READY_TO_PLAY',
  PLAYING: 'PLAYING',
  PAUSED: 'PAUSED',
  ERROR: 'ERROR'
};

// Application State
const appState = {
  currentState: States.IDLE,
  currentScenario: 'SHARP',
  speechRate: 1.0,
  activeSection: 0,
  activeSegment: 0,
  isPlaying: false,
  speechTimer: null,
  isTtsSupported: 'speechSynthesis' in window
};

// DOM References
const el = {
  badge: document.getElementById('activeStateBadge'),
  badgeText: document.getElementById('activeStateText'),
  hapticText: document.getElementById('txtHapticEvent'),
  spokenText: document.getElementById('txtSpokenCue'),
  spectrum: document.getElementById('audioSpectrum'),
  camViewport: document.getElementById('camViewport'),
  camImage: document.getElementById('camImage'),
  glareOverlay: document.getElementById('glareOverlay'),
  framingBox: document.getElementById('framingBox'),
  framingTag: document.getElementById('framingTag'),
  valSharpness: document.getElementById('valSharpness'),
  barSharpness: document.getElementById('barSharpness'),
  valGlare: document.getElementById('valGlare'),
  barGlare: document.getElementById('barGlare'),
  valExposure: document.getElementById('valExposure'),
  barExposure: document.getElementById('barExposure'),
  valFraming: document.getElementById('valFraming'),
  barFraming: document.getElementById('barFraming'),
  decisionBanner: document.getElementById('qualityDecisionBanner'),
  decisionText: document.getElementById('txtQualityDecision'),
  playbackStatus: document.getElementById('playbackStatusBadge'),
  activeSpeechUtterance: document.getElementById('txtActiveSpeechUtterance'),
  speechPosition: document.getElementById('txtSpeechPosition'),
  speechProgress: document.getElementById('barSpeechProgress'),
  iconPlay: document.getElementById('iconPlay'),
  iconPause: document.getElementById('iconPause'),
  btnPlayPause: document.getElementById('btnPlayPause'),
  btnSpeed: document.getElementById('btnSpeed'),
  docScroll: document.getElementById('docContentScroll'),
  leftRing: document.getElementById('leftHapticRing'),
  rightRing: document.getElementById('rightHapticRing'),
  pipelineSteps: document.querySelectorAll('.pipeline-step')
};

// Sentence Segments
const sentences = Array.from(document.querySelectorAll('.speakable-sentence'));

// Initialize
document.addEventListener('DOMContentLoaded', () => {
  setupEventListeners();
  updateState(States.IDLE);
  
  const params = new URLSearchParams(window.location.search);
  const scenario = params.get('scenario') || 'SHARP';
  applyScenario(scenario);

  if (params.get('state') === 'PLAYING') {
    updateState(States.PLAYING);
    appState.isPlaying = true;
    el.iconPlay.classList.add('hidden');
    el.iconPause.classList.remove('hidden');
    el.playbackStatus.textContent = 'PLAYING';
    el.playbackStatus.className = 'badge-pill badge-green';
    el.spectrum.classList.add('active');
    seekToSentence(1);
    el.activeSpeechUtterance.textContent = `"Newton's Second Law describes the quantitative relationship among force, mass, and acceleration."`;
    el.speechProgress.style.width = '65%';
    triggerTactileWaveform('NAVIGATION_TICK', 30);
  } else if (params.get('state') === 'PROCESSING') {
    updateState(States.PROCESSING);
    triggerTactileWaveform('PROCESSING_STARTED', 80);
    speakVoiceCue('Analyzing with Vision AI. Generating study guide.');
  }
});

// Event Listeners Setup
function setupEventListeners() {
  // Shoe Buttons
  document.getElementById('btnLeftTap').addEventListener('click', () => handleLeftFootTap());
  document.getElementById('btnLeftDouble').addEventListener('click', () => handleLeftFootDoubleTap());
  document.getElementById('btnLeftLong').addEventListener('click', () => handleLeftFootLongPress());

  document.getElementById('btnRightTap').addEventListener('click', () => handleRightFootTap());
  document.getElementById('btnRightDouble').addEventListener('click', () => handleRightFootDoubleTap());
  document.getElementById('btnRightLong').addEventListener('click', () => handleRightFootLongPress());

  document.getElementById('leftHeelSwitch').addEventListener('click', () => handleLeftFootTap());
  document.getElementById('rightHeelSwitch').addEventListener('click', () => handleRightFootTap());

  // Playback Controls
  el.btnPlayPause.addEventListener('click', () => handleRightFootTap());
  document.getElementById('btnPrevSentence').addEventListener('click', () => stepSentence(-1));
  document.getElementById('btnNextSentence').addEventListener('click', () => stepSentence(1));
  document.getElementById('btnRepeat').addEventListener('click', () => handleRightFootDoubleTap());

  el.btnSpeed.addEventListener('click', () => {
    const rates = [0.75, 1.0, 1.25, 1.5, 2.0];
    const currentIdx = rates.indexOf(appState.speechRate);
    appState.speechRate = rates[(currentIdx + 1) % rates.length];
    el.btnSpeed.textContent = `${appState.speechRate.toFixed(2)}x`;
  });

  // Scenario Buttons
  document.querySelectorAll('.btn-scenario').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.btn-scenario').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      applyScenario(btn.dataset.scenario);
    });
  });

  // Sentences click-to-play
  sentences.forEach((sentence, index) => {
    sentence.addEventListener('click', () => {
      seekToSentence(index);
      if (!appState.isPlaying) {
        startPlayback();
      }
    });
  });

  // Auto Demo
  document.getElementById('btnAutoDemo').addEventListener('click', () => runAutoDemo());
  document.getElementById('btnReset').addEventListener('click', () => resetSystem());

  // Keyboard Navigation
  window.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
    const key = e.key.toLowerCase();
    if (key === 'l' || key === 'arrowleft') {
      handleLeftFootTap();
    } else if (key === 'r' || key === 'arrowright' || key === ' ') {
      e.preventDefault();
      handleRightFootTap();
    } else if (key === 'escape') {
      resetSystem();
    } else if (['1', '2', '3', '4', '5', '6'].includes(key)) {
      const scenarios = ['SHARP', 'BLURRY', 'CROPPED', 'DARK', 'GLARE', 'OFFLINE'];
      const target = scenarios[parseInt(key, 10) - 1];
      const btn = document.querySelector(`.btn-scenario[data-scenario="${target}"]`);
      if (btn) btn.click();
    }
  });
}

// State Machine Controller
function updateState(newState) {
  appState.currentState = newState;
  el.badgeText.textContent = newState.replace(/_/g, ' ');

  // Update badge styling
  el.badge.className = 'state-badge';
  switch (newState) {
    case States.IDLE:
      el.badge.classList.add('state-idle');
      break;
    case States.SCANNER_STARTING:
    case States.SCANNING:
      el.badge.classList.add('state-scanning');
      break;
    case States.DOCUMENT_READY:
      el.badge.classList.add('state-ready');
      break;
    case States.CAPTURING:
    case States.CAPTURED:
    case States.CAPTURE_VALIDATION:
    case States.PROCESSING:
      el.badge.classList.add('state-processing');
      break;
    case States.READY_TO_PLAY:
    case States.PLAYING:
      el.badge.classList.add('state-playing');
      break;
    case States.PAUSED:
      el.badge.classList.add('state-paused');
      break;
  }

  // Update pipeline ribbon highlight
  el.pipelineSteps.forEach(step => {
    const s = step.dataset.state;
    step.classList.toggle('active', s === newState || (newState === States.READY_TO_PLAY && s === 'PLAYING'));
  });
}

// Left Foot Actions
function handleLeftFootTap() {
  triggerHaptic(el.leftRing, 'LEFT_TAP');

  switch (appState.currentState) {
    case States.IDLE:
      startScanningProcess();
      break;
    case States.SCANNER_STARTING:
    case States.SCANNING:
    case States.DOCUMENT_READY:
      speakVoiceCue('Scanning cancelled.');
      triggerTactileWaveform('SCANNER_STOPPED', 100);
      updateState(States.IDLE);
      break;
    case States.PLAYING:
    case States.PAUSED:
    case States.READY_TO_PLAY:
      stepSentence(-1);
      break;
  }
}

function handleLeftFootDoubleTap() {
  triggerHaptic(el.leftRing, 'LEFT_DOUBLE_TAP');
  if (appState.currentState === States.PLAYING || appState.currentState === States.PAUSED) {
    speakVoiceCue('Rewinding 15 seconds.');
    stepSentence(-2);
  }
}

function handleLeftFootLongPress() {
  triggerHaptic(el.leftRing, 'LEFT_LONG_PRESS');
  if (appState.currentState === States.PLAYING || appState.currentState === States.PAUSED) {
    speakVoiceCue('Previous section.');
    jumpSection(-1);
  }
}

// Right Foot Actions
function handleRightFootTap() {
  triggerHaptic(el.rightRing, 'RIGHT_TAP');

  switch (appState.currentState) {
    case States.DOCUMENT_READY:
      executeCaptureAndProcessing();
      break;
    case States.READY_TO_PLAY:
    case States.PAUSED:
      startPlayback();
      break;
    case States.PLAYING:
      pausePlayback();
      break;
    case States.IDLE:
      speakVoiceCue('Tap Left foot first to activate page scanner.');
      break;
  }
}

function handleRightFootDoubleTap() {
  triggerHaptic(el.rightRing, 'RIGHT_DOUBLE_TAP');
  if (appState.currentState === States.PLAYING || appState.currentState === States.PAUSED) {
    speakVoiceCue('Repeating paragraph.');
    triggerTactileWaveform('NAVIGATION_TICK', 40);
    seekToSentence(appState.activeSegment);
    if (!appState.isPlaying) startPlayback();
  }
}

function handleRightFootLongPress() {
  triggerHaptic(el.rightRing, 'RIGHT_LONG_PRESS');
  if (appState.currentState === States.PLAYING || appState.currentState === States.PAUSED) {
    speakVoiceCue('Next section.');
    jumpSection(1);
  }
}

// Scanning Flow
function startScanningProcess() {
  updateState(States.SCANNER_STARTING);
  triggerTactileWaveform('SCANNER_ACTIVATED', 100);
  speakVoiceCue('Scanner activated. Align page.');

  setTimeout(() => {
    updateState(States.SCANNING);
    checkCameraQuality();
  }, 600);
}

// Capture & AI Processing Workflow
function executeCaptureAndProcessing() {
  updateState(States.CAPTURING);
  triggerTactileWaveform('CAPTURED', 200);
  speakVoiceCue('Capturing page.');

  setTimeout(() => {
    updateState(States.CAPTURED);
    setTimeout(() => {
      updateState(States.CAPTURE_VALIDATION);
      setTimeout(() => {
        updateState(States.PROCESSING);
        triggerTactileWaveform('PROCESSING_STARTED', 80);
        speakVoiceCue('Analyzing with Vision AI. Generating study guide.');

        // Simulate streaming sections
        let tickCount = 0;
        const processInterval = setInterval(() => {
          tickCount++;
          triggerTactileWaveform('PROCESSING_TICK', 30);
          if (tickCount >= 4) {
            clearInterval(processInterval);
            finishAiProcessing();
          }
        }, 500);

      }, 500);
    }, 400);
  }, 500);
}

function finishAiProcessing() {
  updateState(States.READY_TO_PLAY);
  triggerTactileWaveform('PROCESSING_COMPLETED', 250);
  speakVoiceCue('Study guide ready. Tap Right Foot to listen.');
  el.playbackStatus.textContent = 'READY';
  el.playbackStatus.className = 'badge-pill badge-green';

  seekToSentence(0);
}

// Playback Logic
function startPlayback() {
  updateState(States.PLAYING);
  appState.isPlaying = true;
  el.iconPlay.classList.add('hidden');
  el.iconPause.classList.remove('hidden');
  el.playbackStatus.textContent = 'PLAYING';
  el.playbackStatus.className = 'badge-pill badge-green';
  el.spectrum.classList.add('active');

  playCurrentSentence();
}

function pausePlayback() {
  updateState(States.PAUSED);
  appState.isPlaying = false;
  el.iconPlay.classList.remove('hidden');
  el.iconPause.classList.add('hidden');
  el.playbackStatus.textContent = 'PAUSED';
  el.playbackStatus.className = 'badge-pill badge-amber';
  el.spectrum.classList.remove('active');

  if (appState.speechTimer) clearTimeout(appState.speechTimer);
  if (appState.isTtsSupported) window.speechSynthesis.cancel();
}

function playCurrentSentence() {
  if (!appState.isPlaying) return;

  const currentSentenceEl = sentences[appState.activeSegment];
  if (!currentSentenceEl) {
    pausePlayback();
    updateState(States.READY_TO_PLAY);
    return;
  }

  // Highlight in DOM
  sentences.forEach(s => s.classList.remove('speaking'));
  currentSentenceEl.classList.add('speaking');
  currentSentenceEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  // Update utterance text
  const cleanText = currentSentenceEl.textContent.trim();
  el.activeSpeechUtterance.textContent = `"${cleanText}"`;
  el.speechPosition.textContent = `Sentence ${appState.activeSegment + 1} of ${sentences.length}`;

  // Progress Bar Animation
  el.speechProgress.style.width = '0%';
  const durationMs = Math.max(2200, (cleanText.length * 55) / appState.speechRate);
  const startTime = Date.now();

  const progressInterval = setInterval(() => {
    if (!appState.isPlaying) {
      clearInterval(progressInterval);
      return;
    }
    const elapsed = Date.now() - startTime;
    const pct = Math.min(100, (elapsed / durationMs) * 100);
    el.speechProgress.style.width = `${pct}%`;
    if (elapsed >= durationMs) {
      clearInterval(progressInterval);
    }
  }, 50);

  // Audio Speech Synthesis if available
  if (appState.isTtsSupported) {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(cleanText);
    utterance.rate = appState.speechRate;
    window.speechSynthesis.speak(utterance);
  }

  triggerTactileWaveform('NAVIGATION_TICK', 30);

  appState.speechTimer = setTimeout(() => {
    if (appState.isPlaying) {
      if (appState.activeSegment + 1 < sentences.length) {
        appState.activeSegment++;
        playCurrentSentence();
      } else {
        pausePlayback();
        speakVoiceCue('End of study guide reached.');
      }
    }
  }, durationMs);
}

function stepSentence(delta) {
  const target = Math.max(0, Math.min(sentences.length - 1, appState.activeSegment + delta));
  seekToSentence(target);
  if (appState.isPlaying) {
    playCurrentSentence();
  }
}

function seekToSentence(index) {
  appState.activeSegment = index;
  sentences.forEach((s, i) => s.classList.toggle('speaking', i === index));
  const elCurrent = sentences[index];
  if (elCurrent) {
    el.activeSpeechUtterance.textContent = `"${elCurrent.textContent.trim()}"`;
    el.speechPosition.textContent = `Sentence ${index + 1} of ${sentences.length}`;
    elCurrent.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
  el.speechProgress.style.width = '0%';
}

function jumpSection(direction) {
  const currentSentence = sentences[appState.activeSegment];
  const currentSec = parseInt(currentSentence.dataset.sec, 10);
  const targetSec = Math.max(0, Math.min(4, currentSec + direction));

  const firstInSec = sentences.findIndex(s => parseInt(s.dataset.sec, 10) === targetSec);
  if (firstInSec !== -1) {
    seekToSentence(firstInSec);
    if (appState.isPlaying) playCurrentSentence();
  }
}

// Camera Scenario Simulation
function applyScenario(scenario) {
  appState.currentScenario = scenario;
  el.glareOverlay.classList.remove('active');
  el.framingBox.classList.remove('box-warning');
  el.camImage.style.filter = 'none';
  el.camImage.style.transform = 'none';

  switch (scenario) {
    case 'SHARP':
      el.valSharpness.textContent = '92%';
      el.barSharpness.style.width = '92%';
      el.valGlare.textContent = '0.4%';
      el.barGlare.style.width = '2%';
      el.valExposure.textContent = '78%';
      el.barExposure.style.width = '78%';
      el.valFraming.textContent = 'Optimal';
      el.framingTag.textContent = 'PAGE FRAMED & ALIGNED';
      break;

    case 'BLURRY':
      el.camImage.style.filter = 'blur(9px)';
      el.valSharpness.textContent = '19%';
      el.barSharpness.style.width = '19%';
      el.valFraming.textContent = 'Poor';
      el.framingBox.classList.add('box-warning');
      el.framingTag.textContent = 'BLURRY: HOLD STEADY';
      break;

    case 'CROPPED':
      el.camImage.style.transform = 'scale(1.45) translate(-10%, -8%)';
      el.valFraming.textContent = 'Cropped';
      el.barFraming.style.width = '35%';
      el.framingBox.classList.add('box-warning');
      el.framingTag.textContent = 'CROPPED: MOVE HIGHER';
      break;

    case 'DARK':
      el.camImage.style.filter = 'brightness(0.2)';
      el.valExposure.textContent = '14%';
      el.barExposure.style.width = '14%';
      el.framingBox.classList.add('box-warning');
      el.framingTag.textContent = 'TOO DARK: NEED LIGHT';
      break;

    case 'GLARE':
      el.glareOverlay.classList.add('active');
      el.valGlare.textContent = '16.8%';
      el.barGlare.style.width = '84%';
      el.framingBox.classList.add('box-warning');
      el.framingTag.textContent = 'GLARE DETECTED: TILT DEVICE';
      break;

    case 'OFFLINE':
      el.camImage.style.filter = 'brightness(0)';
      el.framingBox.classList.add('box-warning');
      el.framingTag.textContent = 'CAMERA DISCONNECTED';
      break;
  }

  checkCameraQuality();
}

function checkCameraQuality() {
  const isSharp = appState.currentScenario === 'SHARP';

  if (isSharp) {
    el.decisionBanner.className = 'quality-banner banner-ready';
    el.decisionText.textContent = 'DOCUMENT READY: Quality verified. Tap Right Foot to capture.';
    if (appState.currentState === States.SCANNING) {
      updateState(States.DOCUMENT_READY);
      triggerTactileWaveform('DOCUMENT_READY', 120);
      speakVoiceCue('Page ready to capture.');
    }
  } else {
    el.decisionBanner.className = 'quality-banner banner-warning';
    el.decisionText.textContent = `ISSUE DETECTED: ${el.framingTag.textContent}`;
    if (appState.currentState === States.DOCUMENT_READY) {
      updateState(States.SCANNING);
      triggerTactileWaveform('RECOVERABLE_WARNING', 80);
      speakVoiceCue(el.framingTag.textContent);
    }
  }
}

// Actuators & Haptics
function triggerHaptic(container, name) {
  const pulse = document.createElement('div');
  pulse.className = 'haptic-pulse';
  container.appendChild(pulse);
  setTimeout(() => pulse.remove(), 800);
}

function triggerTactileWaveform(name, durationMs) {
  el.hapticText.textContent = `${name} (${durationMs}ms)`;
  el.hapticText.style.color = '#00f0ff';
  setTimeout(() => {
    el.hapticText.style.color = '';
  }, 1200);
}

function speakVoiceCue(text) {
  el.spokenText.textContent = `"${text}"`;
  el.spectrum.classList.add('active');
  setTimeout(() => {
    if (!appState.isPlaying) el.spectrum.classList.remove('active');
  }, 1600);
}

// Reset System
function resetSystem() {
  pausePlayback();
  updateState(States.IDLE);
  speakVoiceCue('System standby.');
  triggerTactileWaveform('IDLE', 0);
  applyScenario('SHARP');
  seekToSentence(0);
}

// Auto Walkthrough Demo
function runAutoDemo() {
  resetSystem();

  setTimeout(() => {
    // 1. Activate Scanner
    handleLeftFootTap();

    setTimeout(() => {
      // 2. Camera verifies sharp page -> auto READY
      setTimeout(() => {
        // 3. Right Foot Tap -> Capture
        handleRightFootTap();

        // 4. Processing completes after ~2.5s, then start playback
        setTimeout(() => {
          handleRightFootTap(); // Start Audio Playback

          // 5. Let it play 2 sentences, then pause
          setTimeout(() => {
            handleRightFootTap(); // Pause

            // 6. Step back sentence
            setTimeout(() => {
              handleLeftFootTap();

              // 7. Resume playback
              setTimeout(() => {
                handleRightFootTap();
              }, 1200);

            }, 1200);

          }, 4500);

        }, 3600);

      }, 1200);

    }, 1000);

  }, 400);
}
