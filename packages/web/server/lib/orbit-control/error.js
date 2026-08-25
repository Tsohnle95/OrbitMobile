export class OrbitControlError extends Error {
  constructor(message, statusCode = 500, details = {}) {
    super(message);
    this.name = 'OrbitControlError';
    this.statusCode = statusCode;
    Object.assign(this, details);
  }
}

export const asControlError = (error, fallbackMessage, fallbackStatus = 500) => {
  if (error instanceof OrbitControlError) return error;
  const message = error instanceof Error ? error.message : fallbackMessage;
  return new OrbitControlError(message || fallbackMessage, Number(error?.statusCode) || fallbackStatus, {
    ...(error?.goalConfigured === true ? { goalConfigured: true } : {}),
  });
};
