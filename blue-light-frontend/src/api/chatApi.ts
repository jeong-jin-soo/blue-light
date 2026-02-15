import axiosClient from './axiosClient';
import type { ChatRequest, ChatResponse } from '../types';

/**
 * AI 챗봇 API
 */
export const sendChatMessage = async (request: ChatRequest): Promise<ChatResponse> => {
  const response = await axiosClient.post<ChatResponse>('/public/chat', request);
  return response.data;
};
