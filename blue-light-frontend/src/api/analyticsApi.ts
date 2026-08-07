import axiosClient from './axiosClient';

export interface KeyCount {
  key: string;
  count: number;
}

export interface DailyStat {
  date: string;
  visitors: number;
  visits: number;
  clicks: number;
}

export interface AnalyticsOverview {
  days: number;
  totalVisits: number;
  uniqueVisitors: number;
  whatsappClicks: number;
  daily: DailyStat[];
  clicksBySource: KeyCount[];
  clicksByCampaign: KeyCount[];
  clicksByService: KeyCount[];
  visitsBySource: KeyCount[];
}

/**
 * 1st-party 유입/문의 분석 개요 (admin).
 * GET /api/admin/analytics/overview?days=
 */
export const getAnalyticsOverview = async (days = 30): Promise<AnalyticsOverview> => {
  const res = await axiosClient.get<AnalyticsOverview>('/admin/analytics/overview', {
    params: { days },
  });
  return res.data;
};

export const analyticsApi = { getAnalyticsOverview };
