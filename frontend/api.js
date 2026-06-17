/**
 * API Client Module
 * Handles HTTP communication with the fraud detection API Gateway endpoint.
 * Uses fetch with AbortController for 10-second timeout enforcement.
 */
const ApiClient = {
  _baseUrl: '',

  /**
   * Set the API base URL.
   * @param {string} baseUrl - The base URL for the API (e.g. "https://api.example.com")
   */
  init(baseUrl) {
    this._baseUrl = baseUrl.replace(/\/+$/, '');
  },

  /**
   * Submit a payment for fraud checking.
   * @param {object} fasterPaymentRequest - The FasterPaymentRequest JSON payload
   * @returns {Promise<object>} Resolves with the FraudDecisionResponse or rejects with a structured error
   */
  submitPayment(fasterPaymentRequest) {
    return this._post('/fraud-check', fasterPaymentRequest);
  },

  /**
   * Confirm a payment that received a REVIEW decision.
   * @param {string} messageId - The messageId from the original fraud decision response
   * @returns {Promise<object>} Resolves with the ConfirmationResponse or rejects with a structured error
   */
  confirmPayment(messageId) {
    return this._post('/confirm-payment', { messageId: messageId });
  },

  /**
   * Internal method to perform a POST request with timeout handling.
   * @param {string} path - The API endpoint path
   * @param {object} body - The request body to send as JSON
   * @returns {Promise<object>} Resolves with parsed JSON or rejects with structured error
   */
  _post(path, body) {
    const url = this._baseUrl + path;
    const controller = new AbortController();
    const timeoutId = setTimeout(function() {
      controller.abort();
    }, 10000);

    return fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body),
      signal: controller.signal
    })
      .then(function(response) {
        clearTimeout(timeoutId);
        if (!response.ok) {
          return response.text().then(function(text) {
            return Promise.reject({
              type: 'http',
              statusCode: response.status,
              description: text || response.statusText
            });
          });
        }
        return response.json();
      })
      .catch(function(error) {
        clearTimeout(timeoutId);
        // If it's already a structured error from the .then() block, re-throw it
        if (error && error.type) {
          return Promise.reject(error);
        }
        // AbortController abort triggers an AbortError
        if (error.name === 'AbortError') {
          return Promise.reject({
            type: 'timeout',
            description: 'Request timed out. Please try again.'
          });
        }
        // Network errors from fetch are TypeErrors
        if (error instanceof TypeError) {
          return Promise.reject({
            type: 'network',
            description: 'Unable to connect. Please check your connection and try again.'
          });
        }
        // Unexpected error — wrap as network error
        return Promise.reject({
          type: 'network',
          description: 'Unable to connect. Please check your connection and try again.'
        });
      });
  }
};
