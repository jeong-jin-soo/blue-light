import axiosClient from './axiosClient';

// ── System Prompt ──────────────────────────────

export const getSystemPrompt = async (): Promise<{ prompt: string; length: number }> => {
  const response = await axiosClient.get<{ prompt: string; length: number }>('/admin/system/prompt');
  return response.data;
};

export const updateSystemPrompt = async (prompt: string): Promise<{ message: string; length: number }> => {
  const response = await axiosClient.put<{ message: string; length: number }>('/admin/system/prompt', { prompt });
  return response.data;
};

export const resetSystemPrompt = async (): Promise<{ message: string; prompt: string; length: number }> => {
  const response = await axiosClient.post<{ message: string; prompt: string; length: number }>('/admin/system/prompt/reset');
  return response.data;
};

// ── Gemini API Key ──────────────────────────────

export interface GeminiKeyStatus {
  configured: boolean;
  source: string;
  maskedKey: string;
  model: string;
  maxTokens: number;
  temperature: number;
}

export const getGeminiApiKeyStatus = async (): Promise<GeminiKeyStatus> => {
  const response = await axiosClient.get<GeminiKeyStatus>('/admin/system/gemini-key');
  return response.data;
};

export const updateGeminiApiKey = async (apiKey: string): Promise<{ message: string }> => {
  const response = await axiosClient.put<{ message: string }>('/admin/system/gemini-key', { apiKey });
  return response.data;
};

export const clearGeminiApiKey = async (): Promise<{ message: string }> => {
  const response = await axiosClient.delete<{ message: string }>('/admin/system/gemini-key');
  return response.data;
};

// ── SLD System Prompt ──────────────────────────────

export const getSldSystemPrompt = async (): Promise<{ prompt: string; length: number }> => {
  const response = await axiosClient.get<{ prompt: string; length: number }>('/admin/system/sld-prompt');
  return response.data;
};

export const updateSldSystemPrompt = async (prompt: string): Promise<{ message: string; length: number }> => {
  const response = await axiosClient.put<{ message: string; length: number }>('/admin/system/sld-prompt', { prompt });
  return response.data;
};

export const resetSldSystemPrompt = async (): Promise<{ message: string; prompt: string; length: number }> => {
  const response = await axiosClient.post<{ message: string; prompt: string; length: number }>('/admin/system/sld-prompt/reset');
  return response.data;
};

// ── SLD AI Generation ──────────────────────────────

export const getSldAiGeneration = async (): Promise<{ enabled: boolean }> => {
  const response = await axiosClient.get<{ enabled: boolean }>('/admin/system/sld-ai-generation');
  return response.data;
};

export const updateSldAiGeneration = async (enabled: boolean): Promise<{ message: string; enabled: boolean }> => {
  const response = await axiosClient.put<{ message: string; enabled: boolean }>('/admin/system/sld-ai-generation', { enabled });
  return response.data;
};

// ── Email Verification ──────────────────────────────

export const getEmailVerification = async (): Promise<{ enabled: boolean }> => {
  const response = await axiosClient.get<{ enabled: boolean }>('/admin/system/email-verification');
  return response.data;
};

export const updateEmailVerification = async (enabled: boolean): Promise<{ message: string; enabled: boolean }> => {
  const response = await axiosClient.put<{ message: string; enabled: boolean }>('/admin/system/email-verification', { enabled });
  return response.data;
};
