/**
 * Result Display Module
 * Renders fraud decision outcomes and manages the confirm-payment flow.
 * Handles ALLOW, REVIEW, BLOCK decisions, error states, and confirmation flow.
 */
const ResultDisplay = {
  _container: null,
  _contentElement: null,
  _sectionElement: null,
  _confirmCallback: null,

  /**
   * Bind the result container element.
   * @param {HTMLElement} containerElement - The result section element (#result-section)
   */
  init(containerElement) {
    this._sectionElement = containerElement;
    this._contentElement = containerElement.querySelector('#result-content');
  },

  /**
   * Render an ALLOW decision with green success styling.
   * @param {object} response - The FraudDecisionResponse payload
   */
  renderAllow(response) {
    var html = '';
    html += '<div class="result-header success">';
    html += '<h2>Payment Approved</h2>';
    html += '</div>';
    html += this._renderRiskScore(response.riskScore);
    html += this._renderBreakdown(response.breakdown);
    html += this._renderRiskFactors(response.riskFactors);
    this._contentElement.innerHTML = html;
    this.show();
  },

  /**
   * Render a REVIEW decision with amber warning styling and confirm button.
   * @param {object} response - The FraudDecisionResponse payload
   * @param {function} onConfirm - Callback when "Confirm Payment" is clicked
   */
  renderReview(response, onConfirm) {
    this._confirmCallback = onConfirm;
    var html = '';
    html += '<div class="result-header warning">';
    html += '<h2>Payment Held for Review</h2>';
    html += '</div>';
    html += this._renderRiskScore(response.riskScore);
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

  /**
   * Render a BLOCK decision with red danger styling.
   * @param {object} response - The FraudDecisionResponse payload
   */
  renderBlock(response) {
    var html = '';
    html += '<div class="result-header danger">';
    html += '<h2>Payment Blocked</h2>';
    html += '</div>';
    html += this._renderRiskScore(response.riskScore);
    html += this._renderBreakdown(response.breakdown);
    html += this._renderRiskFactors(response.riskFactors);
    html += '<p class="result-message danger">This payment cannot proceed due to fraud risk</p>';
    this._contentElement.innerHTML = html;
    this.show();
  },

  /**
   * Render an error message for HTTP/timeout/network errors.
   * @param {object} error - The structured error object { type, statusCode?, description? }
   */
  renderError(error) {
    var message = '';
    if (error.type === 'http') {
      message = 'Error ' + error.statusCode + ': ' + error.description;
    } else if (error.type === 'timeout') {
      message = 'Request timed out. Please try again.';
    } else {
      message = 'Unable to connect. Please check your connection and try again.';
    }
    var html = '<div class="error-display">';
    html += '<p>' + this._escapeHtml(message) + '</p>';
    html += '</div>';
    this._contentElement.innerHTML = html;
    this.show();
  },

  /**
   * Display confirmation success message after "Confirm Payment" succeeds.
   */
  showConfirmationSuccess() {
    var messageEl = this._contentElement.querySelector('#confirmation-message');
    if (messageEl) {
      messageEl.className = 'result-message success';
      messageEl.textContent = 'Payment confirmed and submitted for processing';
    }
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn) {
      btn.disabled = true;
      btn.classList.remove('loading');
    }
  },

  /**
   * Display confirmation error message and re-enable retry.
   */
  showConfirmationError() {
    var messageEl = this._contentElement.querySelector('#confirmation-message');
    if (messageEl) {
      messageEl.className = 'result-message danger';
      messageEl.textContent = 'Confirmation failed. Please try again.';
    }
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn) {
      btn.disabled = false;
      btn.classList.remove('loading');
    }
  },

  /**
   * Toggle the confirm button loading state.
   * @param {boolean} isLoading - Whether to show loading state
   */
  setConfirmLoading(isLoading) {
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (!btn) return;
    if (isLoading) {
      btn.disabled = true;
      btn.classList.add('loading');
    } else {
      btn.disabled = false;
      btn.classList.remove('loading');
    }
  },

  /**
   * Hide the result section.
   */
  hide() {
    this._sectionElement.classList.remove('visible');
  },

  /**
   * Show the result section.
   */
  show() {
    this._sectionElement.classList.add('visible');
  },

  // --- Private helpers ---

  /**
   * Render the risk score display.
   * @param {number} riskScore - The risk score value
   * @returns {string} HTML string
   */
  _renderRiskScore(riskScore) {
    return '<div class="risk-score">Risk Score: <strong>' + riskScore + '</strong></div>';
  },

  /**
   * Render the risk breakdown grid.
   * @param {object} breakdown - The breakdown object with component scores
   * @returns {string} HTML string
   */
  _renderBreakdown(breakdown) {
    if (!breakdown) return '';
    var html = '<div class="risk-breakdown">';
    html += '<div class="risk-breakdown-item">Amount Score: <span>' + breakdown.amountScore + '</span></div>';
    html += '<div class="risk-breakdown-item">CoP Score: <span>' + breakdown.copScore + '</span></div>';
    html += '<div class="risk-breakdown-item">Behavioural Score: <span>' + breakdown.behaviouralScore + '</span></div>';
    html += '<div class="risk-breakdown-item">Channel Score: <span>' + breakdown.channelScore + '</span></div>';
    if (breakdown.ipScore !== undefined && breakdown.ipScore !== null) {
      html += '<div class="risk-breakdown-item">IP Score: <span>' + breakdown.ipScore + '</span></div>';
    }
    if (breakdown.purposeScore !== undefined && breakdown.purposeScore !== null) {
      html += '<div class="risk-breakdown-item">Purpose Score: <span>' + breakdown.purposeScore + '</span></div>';
    }
    html += '</div>';
    return html;
  },

  /**
   * Render the risk factors list.
   * Hides the section if the list is empty or not provided.
   * @param {Array} riskFactors - Array of { category, explanation } objects
   * @returns {string} HTML string
   */
  _renderRiskFactors(riskFactors) {
    if (!riskFactors || riskFactors.length === 0) return '';
    var html = '<div class="risk-factors">';
    html += '<h3>Risk Factors</h3>';
    html += '<ul>';
    for (var i = 0; i < riskFactors.length; i++) {
      var factor = riskFactors[i];
      html += '<li><strong>' + this._escapeHtml(factor.category) + '</strong>: ' + this._escapeHtml(factor.explanation) + '</li>';
    }
    html += '</ul>';
    html += '</div>';
    return html;
  },

  /**
   * Bind click handler to the confirm payment button.
   */
  _bindConfirmButton() {
    var self = this;
    var btn = this._contentElement.querySelector('#confirm-payment-btn');
    if (btn && self._confirmCallback) {
      btn.addEventListener('click', function() {
        self._confirmCallback();
      });
    }
  },

  /**
   * Escape HTML special characters to prevent XSS.
   * @param {string} str - The string to escape
   * @returns {string} Escaped string
   */
  _escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
};
