// ── Re-export Hub ──────────────────────────────
// Domain-separated admin API modules re-exported for backward compatibility.
// New code should import directly from the domain modules.

export {
  getDashboard,
  getApplications,
  getApplication,
  updateStatus,
  requestRevision,
  approveForPayment,
  completeApplication,
  confirmPayment,
  getPayments,
  uploadFile,
  getAdminSldRequest,
  uploadSldComplete,
  confirmSld,
} from './adminApplicationApi';

export {
  getAvailableLews,
  assignLew,
  unassignLew,
} from './adminLewApi';

export {
  getPrices,
  updatePrice,
  getSettings,
  updateSettings,
} from './adminPriceSettingsApi';

export {
  getUsers,
  changeUserRole,
  approveLew,
  rejectLew,
} from './adminUserApi';

// ── Default export (backward compatibility) ──────────────────────────────

import * as applicationApi from './adminApplicationApi';
import * as lewApi from './adminLewApi';
import * as priceSettingsApi from './adminPriceSettingsApi';
import * as userApi from './adminUserApi';

export const adminApi = {
  ...applicationApi,
  ...lewApi,
  ...priceSettingsApi,
  ...userApi,
};

export default adminApi;
