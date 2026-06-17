/**
 * Result Display Module
 * Renders fraud decision outcomes with dark-themed styling.
 */
const ResultDisplay = {
  _container: null,
  _contentElement: null,
  _sectionElement: null,
  _confirmCallback: null,

  init(containerElement) {
    this._sectionElement = containerElement;
    this._contentElement = containerElement.querySelector('#result-content');
  },

  renderAllow(response) {
    var html = '';
    html += '<div class="result-badge allow">✓ PAYMENT APPROVED</div>';
    html += this._renderScore(response.riskScore);
    html += this._renderBreakdown(response.breakdown);
    html += this._renderRiskFactors(response.riskFactors);
    this._contentElement.innerHTML = html;
    this.show();
  },

  renderReview(response, onConfirm) {
    this._confirmCallback = onConfirm;
    var html = '';
    html += '<div class="result-badge review">⚠ HELD FOR REVIEW</div>';
    html += this._renderScore(response.riskScore);
    html += this._renderBreakdown(response.breakdown);
    html += this._renderRiskFactors(response.riskFactors);
    html += '<div class="result-actions">';
    html += '<button type="button" id="confirm-payment-btn" class="btn btn-confirm">';
    html += '<span class="btn-text">Confirm Payment</span>';
    html += '<span class="spinner" aria-hidden="true"></span>';
    html += '</button>';
    html += '</div>';
    html += '<div id="confirmation-message" class="result-message hidden"></div>';
    this._contentElement.innerHTML = html;
    this._bindConfirmButton();
    this.show();
  },

  renderBlock(response) {
    var html = '';
    html += '<div class="result-badge block">✕ PAYMENT BLOCKED</div>';
    html += this._renderScore(response.riskScore);
    html += this._renderBreakdown(response.breakdown);
    html += this._renderRiskFactors(response.riskFactors);
    html += '<p class="result-message danger">This payment cannot proceed due to elevated fraud risk.</p>';
    this._contentElement.innerHTML = html;
    this.show();
  },

  renderError(error) {
    var message = '';
    if (error.type === 'http') {
      message = 'Error ' + error.statusCode + ': ' + error.description;
    } else if (error.type === 'timeout') {
      message = 'Request timed out. Please try again.';
    } else {
      message = 'Unable to connect. Please check your connection.';
    }
    this._contentElement.innerHTML = '<div class="error-display"><p>' + this._escapeHtml(message) + '</p></div>';
    this.show();
  },

  showConfirmationSuccess() {
    var el = this._contentElement.querySelector('#confirmation-message');
    if (el) { el.className = 'result-message success'; el.textContent = 'Payment confirmed and submitted.'; }
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn) { btn.disabled = true; btn.classList.remove('loading'); }
  },

  showConfirmationError() {
    var el = this._contentElement.querySelector('#confirmation-message');
    if (el) { el.className = 'result-message danger'; el.textContent = 'Confirmation failed. Try again.'; }
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn) { btn.disabled = false; btn.classList.remove('loading'); }
  },

  setConfirmLoading(isLoading) {
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (!btn) return;
    btn.disabled = isLoading;
    btn.classList.toggle('loading', isLoading);
  },

  hide() { this._sectionElement.classList.remove('visible'); },
  show() { this._sectionElement.classList.add('visible'); },

  _renderScore(riskScore) {
    return '<div class="score-display"><span class="number">' + riskScore + '</span><span class="label">Risk Score</span></div>';
  },

  _renderBreakdown(breakdown) {
    if (!breakdown) return '';
    var html = '<div class="breakdown-grid">';
    html += this._breakdownItem(breakdown.amountScore, 'Amount');
    html += this._breakdownItem(breakdown.copScore, 'CoP');
    html += this._breakdownItem(breakdown.behaviouralScore, 'Behaviour');
    html += this._breakdownItem(breakdown.channelScore, 'Channel');
    if (breakdown.ipScore !== undefined) html += this._breakdownItem(breakdown.ipScore, 'IP Intel');
    if (breakdown.purposeScore !== undefined) html += this._breakdownItem(breakdown.purposeScore, 'Purpose');
    html += '</div>';
    return html;
  },

  _breakdownItem(value, name) {
    return '<div class="breakdown-item"><div class="value">' + value + '</div><div class="name">' + name + '</div></div>';
  },

  _renderRiskFactors(riskFactors) {
    if (!riskFactors || riskFactors.length === 0) return '';
    var html = '<div class="risk-factors"><h3>Risk Factors</h3>';
    for (var i = 0; i < riskFactors.length; i++) {
      var f = riskFactors[i];
      html += '<div class="risk-factor-item"><strong>' + this._escapeHtml(f.category) + '</strong>';
      html += '<p>' + this._escapeHtml(f.explanation) + '</p></div>';
    }
    html += '</div>';
    return html;
  },

  _bindConfirmButton() {
    var self = this;
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn && self._confirmCallback) {
      btn.addEventListener('click', function() { self._confirmCallback(); });
    }
  },

  _escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#039;');
  }
};
