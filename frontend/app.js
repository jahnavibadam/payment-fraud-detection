/**
 * App Controller Module
 * Coordinates all modules and handles the overall application flow.
 * Wires form submission, API calls, result display, and user actions.
 */
const App = {
  _lastMessageId: null,

  /**
   * Initialize all modules and wire event handlers.
   * @param {object} config - Configuration object
   * @param {string} config.apiBaseUrl - Base URL for the fraud detection API
   * @param {HTMLFormElement} config.formElement - The payment form DOM element
   * @param {HTMLElement} config.resultElement - The result section DOM element
   */
  init(config) {
    var self = this;
    this._lastMessageId = null;

    // Initialize API client
    ApiClient.init(config.apiBaseUrl);

    // Initialize result display
    ResultDisplay.init(config.resultElement);

    // Initialize payment form with submit handler
    PaymentForm.init(config.formElement, function() {
      self._handleSubmit();
    });

    // Wire "New Payment" button
    var newPaymentBtn = document.getElementById('new-payment-btn');
    if (newPaymentBtn) {
      newPaymentBtn.addEventListener('click', function() {
        self._handleNewPayment();
      });
    }
  },

  /**
   * Handle form submission: validate, build payload, call API, display result.
   */
  _handleSubmit() {
    var self = this;

    // Get form data
    var formData = PaymentForm.getFormData();

    // Clear previous errors
    PaymentForm.clearErrors();

    // Validate
    var result = Validator.validate(formData);
    if (!result.valid) {
      PaymentForm.showErrors(result.errors);
      return;
    }

    // Build FasterPaymentRequest payload
    var request = self._buildPaymentRequest(formData);
    self._lastMessageId = request.messageId;

    // Set loading state
    PaymentForm.setLoading(true);

    // Submit to API
    ApiClient.submitPayment(request)
      .then(function(response) {
        PaymentForm.setLoading(false);
        self._handleResponse(response);
      })
      .catch(function(error) {
        PaymentForm.setLoading(false);
        ResultDisplay.renderError(error);
      });
  },

  /**
   * Build a FasterPaymentRequest payload from form data.
   * @param {object} formData - The structured form data from PaymentForm.getFormData()
   * @returns {object} The FasterPaymentRequest JSON payload
   */
  _buildPaymentRequest(formData) {
    // Build IP intelligence object if any IP fields are populated
    var ipIntelligence = null;
    if (formData.ipIsVpn || formData.ipIsProxy || formData.ipIsTor ||
        formData.ipIsHighRiskGeo || formData.ipVelocityFlag || formData.ipIsNewIp ||
        formData.ipCountry || formData.ipReputationScore) {
      ipIntelligence = {
        ipAddress: '192.168.1.1',
        country: formData.ipCountry || null,
        region: null,
        isVpn: formData.ipIsVpn,
        isProxy: formData.ipIsProxy,
        isTor: formData.ipIsTor,
        ipReputationScore: formData.ipReputationScore ? parseInt(formData.ipReputationScore, 10) : 0,
        isHighRiskGeo: formData.ipIsHighRiskGeo,
        velocityFlag: formData.ipVelocityFlag,
        isNewIp: formData.ipIsNewIp,
        lastSeenTimestamp: new Date().toISOString()
      };
    }

    // Build purpose analysis object if any purpose fields are populated
    var purposeAnalysis = null;
    if (formData.purposeCategory || formData.scamIndicator !== 'NONE' || formData.historicalDeviation) {
      purposeAnalysis = {
        declaredPurpose: formData.paymentReference || null,
        purposeCategory: formData.purposeCategory || null,
        scamIndicator: formData.scamIndicator || 'NONE',
        confidenceScore: formData.confidenceScore ? parseFloat(formData.confidenceScore) : 0.9,
        historicalDeviation: formData.historicalDeviation
      };
    }

    return {
      messageId: this._generateUUID(),
      debtorAccount: {
        sortCode: formData.debtorSortCode.replace(/-/g, ''),
        accountNumber: formData.debtorAccountNumber,
        accountName: formData.debtorAccountName
      },
      creditorAccount: {
        sortCode: formData.creditorSortCode.replace(/-/g, ''),
        accountNumber: formData.creditorAccountNumber,
        accountName: formData.creditorAccountName
      },
      amount: parseFloat(formData.amount),
      currency: 'GBP',
      paymentReference: formData.paymentReference,
      confirmationOfPayee: {
        result: formData.copResult,
        matchedName: formData.copMatchedName
      },
      channel: {
        type: formData.channelType,
        deviceId: null,
        geoLocation: null,
        sessionDuration: null,
        ipIntelligence: ipIntelligence,
        purposeAnalysis: purposeAnalysis
      },
      timestamp: new Date().toISOString()
    };
  },

  /**
   * Route the API response to the correct render method based on decision type.
   * @param {object} response - The FraudDecisionResponse from the API
   */
  _handleResponse(response) {
    var self = this;

    // Store messageId from response for confirm flow
    if (response.messageId) {
      self._lastMessageId = response.messageId;
    }

    switch (response.decision) {
      case 'ALLOW':
        ResultDisplay.renderAllow(response);
        PaymentForm.hide();
        break;
      case 'REVIEW':
        ResultDisplay.renderReview(response, function() {
          self._handleConfirmPayment();
        });
        PaymentForm.hide();
        break;
      case 'BLOCK':
        ResultDisplay.renderBlock(response);
        PaymentForm.hide();
        break;
      default:
        ResultDisplay.renderError({
          type: 'http',
          statusCode: 0,
          description: 'Unexpected decision type: ' + response.decision
        });
        break;
    }
  },

  /**
   * Handle "Confirm Payment" button click for REVIEW decisions.
   * Calls ApiClient.confirmPayment with the messageId from the original response.
   */
  _handleConfirmPayment() {
    ResultDisplay.setConfirmLoading(true);

    ApiClient.confirmPayment(this._lastMessageId)
      .then(function() {
        ResultDisplay.setConfirmLoading(false);
        ResultDisplay.showConfirmationSuccess();
      })
      .catch(function() {
        ResultDisplay.setConfirmLoading(false);
        ResultDisplay.showConfirmationError();
      });
  },

  /**
   * Handle "New Payment" button click.
   * Resets the form, shows it, and hides the result display.
   */
  _handleNewPayment() {
    PaymentForm.reset();
    PaymentForm.show();
    ResultDisplay.hide();
  },

  /**
   * Generate a UUID v4 string.
   * Uses crypto.randomUUID() if available, otherwise falls back to manual implementation.
   * @returns {string} A UUID v4 string
   */
  _generateUUID() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    // Fallback UUID v4 implementation
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
      var r = (Math.random() * 16) | 0;
      var v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
};

// Auto-initialize on DOMContentLoaded
document.addEventListener('DOMContentLoaded', function() {
  var dataAttr = document.body.getAttribute('data-api-base-url');
  var apiBaseUrl = (dataAttr !== null) ? dataAttr
    : (window.API_BASE_URL !== undefined ? window.API_BASE_URL : 'http://localhost:3000');

  var formElement = document.getElementById('payment-form');
  var resultElement = document.getElementById('result-section');

  App.init({
    apiBaseUrl: apiBaseUrl,
    formElement: formElement,
    resultElement: resultElement
  });
});
