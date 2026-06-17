/**
 * Validation Module - Pure validation functions for the Payment Fraud UI.
 * Validates form data and returns structured error arrays.
 * All errors are collected simultaneously (never short-circuits on first failure).
 */
const Validator = {
  /**
   * Validates the complete form data object.
   * @param {object} formData - The form data to validate.
   * @returns {{ valid: boolean, errors: Array<{ field: string, message: string }> }}
   */
  validate(formData) {
    const errors = [];

    // Sort code validation (debtor)
    errors.push(...this._validateSortCode(formData.debtorSortCode, 'debtorSortCode', 'Debtor sort code'));

    // Account number validation (debtor)
    errors.push(...this._validateAccountNumber(formData.debtorAccountNumber, 'debtorAccountNumber', 'Debtor account number'));

    // Sort code validation (creditor)
    errors.push(...this._validateSortCode(formData.creditorSortCode, 'creditorSortCode', 'Creditor sort code'));

    // Account number validation (creditor)
    errors.push(...this._validateAccountNumber(formData.creditorAccountNumber, 'creditorAccountNumber', 'Creditor account number'));

    // Amount validation
    errors.push(...this._validateAmount(formData.amount));

    // Channel type validation
    errors.push(...this._validateChannelType(formData.channelType));

    return {
      valid: errors.length === 0,
      errors: errors
    };
  },

  /**
   * Validates a sort code field.
   * Accepts NN-NN-NN or NNNNNN format (exactly 6 digits after stripping hyphens).
   * @param {string} value - The sort code value.
   * @param {string} field - The field identifier.
   * @param {string} label - The human-readable field label.
   * @returns {Array<{ field: string, message: string }>}
   */
  _validateSortCode(value, field, label) {
    const errors = [];

    if (!value || value.trim() === '') {
      errors.push({ field: field, message: label + ' is required' });
      return errors;
    }

    // Strip hyphens and check for exactly 6 digits
    const stripped = value.replace(/-/g, '');
    if (!/^\d{6}$/.test(stripped)) {
      errors.push({ field: field, message: label + ' must be 6 digits' });
    }

    return errors;
  },

  /**
   * Validates an account number field.
   * Must be exactly 8 digits.
   * @param {string} value - The account number value.
   * @param {string} field - The field identifier.
   * @param {string} label - The human-readable field label.
   * @returns {Array<{ field: string, message: string }>}
   */
  _validateAccountNumber(value, field, label) {
    const errors = [];

    if (!value || value.trim() === '') {
      errors.push({ field: field, message: label + ' is required' });
      return errors;
    }

    if (!/^\d{8}$/.test(value)) {
      errors.push({ field: field, message: label + ' must be 8 digits' });
    }

    return errors;
  },

  /**
   * Validates the amount field.
   * Must be required, numeric, in range [0.01, 999999999.99], and max 2 decimal places.
   * @param {string} value - The amount value.
   * @returns {Array<{ field: string, message: string }>}
   */
  _validateAmount(value) {
    const errors = [];

    if (!value || value.trim() === '') {
      errors.push({ field: 'amount', message: 'Amount is required' });
      return errors;
    }

    const numValue = Number(value);

    // Check if it's a valid number first
    if (isNaN(numValue)) {
      errors.push({ field: 'amount', message: 'Amount must be a number between 0.01 and 999,999,999.99' });
      return errors;
    }

    // Check decimal places (max 2) before range - more specific error
    if (value.includes('.')) {
      const decimalPart = value.split('.')[1];
      if (decimalPart && decimalPart.length > 2) {
        errors.push({ field: 'amount', message: 'Amount must have at most 2 decimal places' });
        return errors;
      }
    }

    // Check range [0.01, 999999999.99]
    if (numValue < 0.01 || numValue > 999999999.99) {
      errors.push({ field: 'amount', message: 'Amount must be a number between 0.01 and 999,999,999.99' });
    }

    return errors;
  },

  /**
   * Validates the channel type field.
   * Must have a selection (non-empty).
   * @param {string} value - The channel type value.
   * @returns {Array<{ field: string, message: string }>}
   */
  _validateChannelType(value) {
    const errors = [];

    if (!value || value.trim() === '') {
      errors.push({ field: 'channelType', message: 'Channel type is required' });
    }

    return errors;
  }
};
