/**
 * Payment Form Module
 * Manages the DOM form element, collects input values, and coordinates submission.
 */
const PaymentForm = {
  _form: null,
  _submitBtn: null,
  _onSubmit: null,

  /**
   * Bind the form element and submit handler.
   * Prevents default form submission and delegates to the provided callback.
   * @param {HTMLFormElement} formElement - The form DOM element
   * @param {function} onSubmit - Callback invoked on valid form submission
   */
  init(formElement, onSubmit) {
    this._form = formElement;
    this._submitBtn = formElement.querySelector('#submit-btn');
    this._onSubmit = onSubmit;

    this._form.addEventListener('submit', (e) => {
      e.preventDefault();
      if (this._onSubmit) {
        this._onSubmit();
      }
    });
  },

  /**
   * Returns the structured form data object from current field values.
   * @returns {object} Form data matching the FasterPaymentRequest input shape
   */
  getFormData() {
    return {
      debtorSortCode: this._form.querySelector('#debtor-sort-code').value.trim(),
      debtorAccountNumber: this._form.querySelector('#debtor-account-number').value.trim(),
      debtorAccountName: this._form.querySelector('#debtor-account-name').value.trim(),
      creditorSortCode: this._form.querySelector('#creditor-sort-code').value.trim(),
      creditorAccountNumber: this._form.querySelector('#creditor-account-number').value.trim(),
      creditorAccountName: this._form.querySelector('#creditor-account-name').value.trim(),
      amount: this._form.querySelector('#amount').value.trim(),
      paymentReference: this._form.querySelector('#payment-reference').value.trim(),
      channelType: this._form.querySelector('#channel-type').value,
      copResult: this._form.querySelector('#cop-result').value,
      copMatchedName: this._form.querySelector('#cop-matched-name').value.trim(),
      ipCountry: this._form.querySelector('#ip-country').value.trim(),
      ipIsVpn: this._form.querySelector('#ip-is-vpn').checked,
      ipIsProxy: this._form.querySelector('#ip-is-proxy').checked,
      ipIsTor: this._form.querySelector('#ip-is-tor').checked,
      ipReputationScore: this._form.querySelector('#ip-reputation-score').value.trim(),
      ipIsHighRiskGeo: this._form.querySelector('#ip-is-high-risk-geo').checked,
      ipVelocityFlag: this._form.querySelector('#ip-velocity-flag').checked,
      ipIsNewIp: this._form.querySelector('#ip-is-new-ip').checked,
    };
  },

  /**
   * Toggle loading state on the submit button.
   * When loading: disables button and adds "loading" class (shows spinner, hides text).
   * When not loading: enables button and removes "loading" class.
   * @param {boolean} isLoading - Whether the form is in a loading state
   */
  setLoading(isLoading) {
    if (isLoading) {
      this._submitBtn.disabled = true;
      this._submitBtn.classList.add('loading');
    } else {
      this._submitBtn.disabled = false;
      this._submitBtn.classList.remove('loading');
    }
  },

  /**
   * Remove all validation error displays from the form.
   * Clears error text, hides error messages, and removes error styling from inputs.
   */
  clearErrors() {
    const errorMessages = this._form.querySelectorAll('.error-message');
    errorMessages.forEach((el) => {
      el.textContent = '';
      el.classList.remove('visible');
    });

    const errorInputs = this._form.querySelectorAll('.error');
    errorInputs.forEach((el) => {
      el.classList.remove('error');
    });
  },

  /**
   * Display inline validation error messages next to their respective fields.
   * Each error object has { field, message } where field maps to the input ID.
   * Uses aria-describedby for accessibility.
   * @param {Array<{field: string, message: string}>} errors - Validation errors to display
   */
  showErrors(errors) {
    errors.forEach((err) => {
      const fieldId = this._fieldToId(err.field);
      const input = this._form.querySelector(`#${fieldId}`);
      const errorSpan = this._form.querySelector(`#${fieldId}-error`);

      if (input) {
        input.classList.add('error');
      }
      if (errorSpan) {
        errorSpan.textContent = err.message;
        errorSpan.classList.add('visible');
      }
    });
  },

  /**
   * Reset all form fields to their default empty state.
   * Clears text inputs, resets dropdowns to no selection, and enables the submit button.
   */
  reset() {
    // Clear all text inputs
    const textInputs = this._form.querySelectorAll('input[type="text"], input[type="number"]');
    textInputs.forEach((input) => {
      input.value = '';
    });

    // Reset dropdowns to first option (no selection)
    const selects = this._form.querySelectorAll('select');
    selects.forEach((select) => {
      select.selectedIndex = 0;
    });

    // Re-enable submit button and remove loading state
    this._submitBtn.disabled = false;
    this._submitBtn.classList.remove('loading');

    // Clear any displayed errors
    this.clearErrors();
  },

  /**
   * Hide the form section.
   */
  hide() {
    this._form.style.display = 'none';
  },

  /**
   * Show the form section.
   */
  show() {
    this._form.style.display = '';
  },

  /**
   * Convert a camelCase field name to the corresponding HTML element ID.
   * e.g., "debtorSortCode" -> "debtor-sort-code"
   * @param {string} field - The camelCase field name
   * @returns {string} The kebab-case HTML element ID
   * @private
   */
  _fieldToId(field) {
    return field.replace(/([A-Z])/g, '-$1').toLowerCase();
  }
};
