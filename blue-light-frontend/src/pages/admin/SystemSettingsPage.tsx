import { useEffect, useState, useCallback, useRef, type ChangeEvent } from 'react';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import * as systemAdminApi from '../../api/systemAdminApi';
import type { GeminiKeyStatus } from '../../api/systemAdminApi';
import sampleFileApi from '../../api/sampleFileApi';
import { DOCUMENT_CATEGORIES, formatFileSize } from '../../utils/applicationUtils';
import type { SampleFileInfo } from '../../types';

/**
 * SYSTEM_ADMIN 전용 — 시스템 설정 관리 페이지
 * - AI 챗봇 시스템 프롬프트
 * - Gemini API 키
 * - 이메일 인증 설정
 */
export default function SystemSettingsPage() {
  const toast = useToastStore();

  // ── System Prompt ──────────────────────────────
  const [prompt, setPrompt] = useState('');
  const [originalPrompt, setOriginalPrompt] = useState('');
  const [loadingPrompt, setLoadingPrompt] = useState(true);
  const [savingPrompt, setSavingPrompt] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);

  // ── Gemini API Key ──────────────────────────────
  const [geminiStatus, setGeminiStatus] = useState<GeminiKeyStatus | null>(null);
  const [newApiKey, setNewApiKey] = useState('');
  const [savingKey, setSavingKey] = useState(false);
  const [showKeyInput, setShowKeyInput] = useState(false);
  const [showClearKeyConfirm, setShowClearKeyConfirm] = useState(false);

  // ── Email Verification ──────────────────────────────
  const [emailVerificationEnabled, setEmailVerificationEnabled] = useState(false);
  const [originalEmailVerification, setOriginalEmailVerification] = useState(false);
  const [savingEmailVerification, setSavingEmailVerification] = useState(false);

  // ── SLD AI Generation ──────────────────────────────
  const [sldAiEnabled, setSldAiEnabled] = useState(true);
  const [originalSldAi, setOriginalSldAi] = useState(true);
  const [savingSldAi, setSavingSldAi] = useState(false);

  // ── SLD System Prompt ──────────────────────────────
  const [sldPrompt, setSldPrompt] = useState('');
  const [originalSldPrompt, setOriginalSldPrompt] = useState('');
  const [savingSldPrompt, setSavingSldPrompt] = useState(false);
  const [showSldResetConfirm, setShowSldResetConfirm] = useState(false);

  // ── Sample Files ──────────────────────────────
  const [sampleFiles, setSampleFiles] = useState<SampleFileInfo[]>([]);
  const [uploadingCategory, setUploadingCategory] = useState<string | null>(null);
  const [deletingCategory, setDeletingCategory] = useState<string | null>(null);
  const [showDeleteSampleConfirm, setShowDeleteSampleConfirm] = useState(false);
  const [deleteCategoryTarget, setDeleteCategoryTarget] = useState<string>('');
  const sampleInputRefs = useRef<Record<string, HTMLInputElement | null>>({});

  // 신청자 업로드 대상 카테고리만 (sld, loa, sp_account, photo)
  const sampleCategories = DOCUMENT_CATEGORIES.filter((c) => c.applicantUpload);

  // ── Data Loading ──────────────────────────────

  const loadData = useCallback(async () => {
    try {
      const [promptData, keyData, emailData, sldAiData, sldPromptData, sampleData] = await Promise.all([
        systemAdminApi.getSystemPrompt(),
        systemAdminApi.getGeminiApiKeyStatus(),
        systemAdminApi.getEmailVerification(),
        systemAdminApi.getSldAiGeneration(),
        systemAdminApi.getSldSystemPrompt(),
        sampleFileApi.getSampleFiles(),
      ]);

      setPrompt(promptData.prompt);
      setOriginalPrompt(promptData.prompt);
      setGeminiStatus(keyData);
      setEmailVerificationEnabled(emailData.enabled);
      setOriginalEmailVerification(emailData.enabled);
      setSldAiEnabled(sldAiData.enabled);
      setOriginalSldAi(sldAiData.enabled);
      setSldPrompt(sldPromptData.prompt);
      setOriginalSldPrompt(sldPromptData.prompt);
      setSampleFiles(sampleData);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to load system settings';
      toast.error(message);
    } finally {
      setLoadingPrompt(false);
    }
  }, [toast]);

  useEffect(() => {
    loadData();
  }, []);

  // ── System Prompt Handlers ──────────────────────────────

  const promptChanged = prompt !== originalPrompt;

  const handleSavePrompt = async () => {
    setSavingPrompt(true);
    try {
      await systemAdminApi.updateSystemPrompt(prompt);
      setOriginalPrompt(prompt);
      toast.success('System prompt updated successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update system prompt';
      toast.error(message);
    } finally {
      setSavingPrompt(false);
    }
  };

  const handleResetPrompt = async () => {
    setSavingPrompt(true);
    try {
      const result = await systemAdminApi.resetSystemPrompt();
      setPrompt(result.prompt);
      setOriginalPrompt(result.prompt);
      toast.success('System prompt reset to default');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to reset system prompt';
      toast.error(message);
    } finally {
      setSavingPrompt(false);
      setShowResetConfirm(false);
    }
  };

  // ── Gemini API Key Handlers ──────────────────────────────

  const handleSaveApiKey = async () => {
    if (!newApiKey.trim()) {
      toast.error('API key cannot be empty');
      return;
    }
    setSavingKey(true);
    try {
      await systemAdminApi.updateGeminiApiKey(newApiKey.trim());
      const updated = await systemAdminApi.getGeminiApiKeyStatus();
      setGeminiStatus(updated);
      setNewApiKey('');
      setShowKeyInput(false);
      toast.success('Gemini API key updated successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update API key';
      toast.error(message);
    } finally {
      setSavingKey(false);
    }
  };

  const handleClearApiKey = async () => {
    setSavingKey(true);
    try {
      await systemAdminApi.clearGeminiApiKey();
      const updated = await systemAdminApi.getGeminiApiKeyStatus();
      setGeminiStatus(updated);
      toast.success('API key cleared — reverted to environment variable');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to clear API key';
      toast.error(message);
    } finally {
      setSavingKey(false);
      setShowClearKeyConfirm(false);
    }
  };

  // ── Email Verification Handlers ──────────────────────────────

  const emailVerificationChanged = emailVerificationEnabled !== originalEmailVerification;

  const handleSaveEmailVerification = async () => {
    setSavingEmailVerification(true);
    try {
      const result = await systemAdminApi.updateEmailVerification(emailVerificationEnabled);
      setOriginalEmailVerification(emailVerificationEnabled);
      toast.success(result.message);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update email verification';
      toast.error(message);
    } finally {
      setSavingEmailVerification(false);
    }
  };

  // ── SLD AI Generation Handlers ──────────────────────────────

  const sldAiChanged = sldAiEnabled !== originalSldAi;

  const handleSaveSldAi = async () => {
    setSavingSldAi(true);
    try {
      const result = await systemAdminApi.updateSldAiGeneration(sldAiEnabled);
      setOriginalSldAi(sldAiEnabled);
      toast.success(result.message);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update SLD AI generation setting';
      toast.error(message);
    } finally {
      setSavingSldAi(false);
    }
  };

  // ── SLD System Prompt Handlers ──────────────────────────────

  const sldPromptChanged = sldPrompt !== originalSldPrompt;

  const handleSaveSldPrompt = async () => {
    setSavingSldPrompt(true);
    try {
      await systemAdminApi.updateSldSystemPrompt(sldPrompt);
      setOriginalSldPrompt(sldPrompt);
      toast.success('SLD system prompt updated successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update SLD system prompt';
      toast.error(message);
    } finally {
      setSavingSldPrompt(false);
    }
  };

  const handleResetSldPrompt = async () => {
    setSavingSldPrompt(true);
    try {
      const result = await systemAdminApi.resetSldSystemPrompt();
      setSldPrompt(result.prompt);
      setOriginalSldPrompt(result.prompt);
      toast.success('SLD system prompt reset to default');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to reset SLD system prompt';
      toast.error(message);
    } finally {
      setSavingSldPrompt(false);
      setShowSldResetConfirm(false);
    }
  };

  // ── Sample File Handlers ──────────────────────────────

  const getSampleForCategory = (categoryKey: string): SampleFileInfo | undefined =>
    sampleFiles.find((f) => f.categoryKey === categoryKey);

  const handleSampleUpload = async (categoryKey: string, e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    // Reset input value
    if (sampleInputRefs.current[categoryKey]) {
      sampleInputRefs.current[categoryKey]!.value = '';
    }

    setUploadingCategory(categoryKey);
    try {
      await sampleFileApi.uploadSampleFile(categoryKey, file);
      const updated = await sampleFileApi.getSampleFiles();
      setSampleFiles(updated);
      toast.success('Sample file uploaded successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to upload sample file';
      toast.error(message);
    } finally {
      setUploadingCategory(null);
    }
  };

  const handleSampleDelete = async () => {
    if (!deleteCategoryTarget) return;
    setDeletingCategory(deleteCategoryTarget);
    try {
      await sampleFileApi.deleteSampleFile(deleteCategoryTarget);
      const updated = await sampleFileApi.getSampleFiles();
      setSampleFiles(updated);
      toast.success('Sample file deleted');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to delete sample file';
      toast.error(message);
    } finally {
      setDeletingCategory(null);
      setShowDeleteSampleConfirm(false);
      setDeleteCategoryTarget('');
    }
  };

  // ── Render ──────────────────────────────

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">System Configuration</h1>
        <p className="text-sm text-gray-500 mt-1">
          Manage system-level settings — chatbot prompt, API keys, and platform configuration
        </p>
      </div>

      {/* ── Email Verification ────────────────────── */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">Email Verification</h2>
        <p className="text-xs text-gray-500 mb-4">
          When enabled, new users must verify their email address before accessing the platform.
          Disable this for local development or testing.
        </p>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={emailVerificationEnabled}
                onChange={(e) => setEmailVerificationEnabled(e.target.checked)}
                className="sr-only peer"
              />
              <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary" />
            </label>
            <span className="text-sm text-gray-700">
              {emailVerificationEnabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              onClick={handleSaveEmailVerification}
              loading={savingEmailVerification}
              disabled={!emailVerificationChanged}
              size="sm"
            >
              Save
            </Button>
            {emailVerificationChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
          </div>
        </div>

        {!emailVerificationEnabled && (
          <p className="text-xs text-amber-600 bg-amber-50 p-2 rounded mt-3">
            Email verification is currently disabled. New users can sign up without verifying their
            email.
          </p>
        )}
      </Card>

      {/* ── Document Sample Files ────────────────────── */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">Document Sample Files</h2>
        <p className="text-xs text-gray-500 mb-4">
          Upload sample files for applicants to reference when preparing their documents.
          Each category supports one sample file at a time.
        </p>

        <div className="space-y-3">
          {sampleCategories.map((category) => {
            const sample = getSampleForCategory(category.key);
            const isUploading = uploadingCategory === category.key;
            const isDeleting = deletingCategory === category.key;

            return (
              <div
                key={category.key}
                className={`flex items-center justify-between p-3 rounded-lg border ${category.borderColor} ${category.bgColor}`}
              >
                <div className="flex items-center gap-3 min-w-0">
                  <span className="text-lg flex-shrink-0">{category.icon}</span>
                  <div className="min-w-0">
                    <p className={`text-sm font-medium ${category.headerColor}`}>{category.label}</p>
                    {sample ? (
                      <p className="text-xs text-gray-500 truncate" title={sample.originalFilename}>
                        {sample.originalFilename} ({formatFileSize(sample.fileSize)})
                      </p>
                    ) : (
                      <p className="text-xs text-gray-400">No sample uploaded</p>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-2 flex-shrink-0">
                  <input
                    ref={(el) => { sampleInputRefs.current[category.key] = el; }}
                    type="file"
                    accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip"
                    onChange={(e) => handleSampleUpload(category.key, e)}
                    className="hidden"
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => sampleInputRefs.current[category.key]?.click()}
                    loading={isUploading}
                    disabled={isUploading || isDeleting}
                  >
                    {sample ? 'Replace' : 'Upload'}
                  </Button>
                  {sample && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setDeleteCategoryTarget(category.key);
                        setShowDeleteSampleConfirm(true);
                      }}
                      disabled={isUploading || isDeleting}
                    >
                      Delete
                    </Button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </Card>

      {/* ── SLD AI Generation ────────────────────── */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">AI SLD Generation</h2>
        <p className="text-xs text-gray-500 mb-4">
          When enabled, administrators and SLD managers can use the AI-powered SLD (Single Line Diagram)
          generation feature. Disable this to prevent AI SLD generation across the platform.
        </p>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={sldAiEnabled}
                onChange={(e) => setSldAiEnabled(e.target.checked)}
                className="sr-only peer"
              />
              <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary" />
            </label>
            <span className="text-sm text-gray-700">
              {sldAiEnabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              onClick={handleSaveSldAi}
              loading={savingSldAi}
              disabled={!sldAiChanged}
              size="sm"
            >
              Save
            </Button>
            {sldAiChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
          </div>
        </div>

        {!sldAiEnabled && (
          <p className="text-xs text-amber-600 bg-amber-50 p-2 rounded mt-3">
            AI SLD generation is currently disabled. Users will not be able to generate SLD diagrams
            using the AI chatbot.
          </p>
        )}
      </Card>

      {/* ── SLD System Prompt ────────────────────── */}
      <Card>
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-4">
          <div>
            <h2 className="text-lg font-semibold text-gray-800">AI SLD Generation Prompt</h2>
            <p className="text-xs text-gray-500 mt-0.5">
              The system prompt that guides the AI when generating Single Line Diagrams (SLD).
              Includes Singapore electrical standards, design rules, and conversation flow.
              {sldPrompt.length > 0 && ` (${sldPrompt.length.toLocaleString()} characters)`}
            </p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {sldPromptChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowSldResetConfirm(true)}
              disabled={savingSldPrompt}
            >
              Reset Default
            </Button>
            <Button
              size="sm"
              onClick={handleSaveSldPrompt}
              loading={savingSldPrompt}
              disabled={!sldPromptChanged}
            >
              Save Prompt
            </Button>
          </div>
        </div>

        {loadingPrompt ? (
          <div className="h-64 bg-gray-100 rounded-lg animate-pulse" />
        ) : (
          <textarea
            value={sldPrompt}
            onChange={(e) => setSldPrompt(e.target.value)}
            rows={20}
            className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm font-mono
                       leading-relaxed resize-y
                       focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary
                       placeholder:text-gray-400"
            placeholder="Enter SLD system prompt..."
          />
        )}

        <p className="text-xs text-gray-400 mt-2">
          Changes take effect within 60 seconds for new SLD generation sessions. Existing conversations
          will use the updated prompt on the next message.
        </p>
      </Card>

      {/* ── Gemini API Key ────────────────────── */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">Gemini API Key</h2>
        <p className="text-xs text-gray-500 mb-4">
          Manages the Google Gemini API key for the AI chatbot. You can override the environment
          variable by setting a key here.
        </p>

        {geminiStatus && (
          <div className="space-y-3">
            {/* Status info */}
            <div className="bg-gray-50 rounded-lg p-3 space-y-2">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${geminiStatus.configured ? 'bg-green-500' : 'bg-red-500'}`} />
                <span className="text-sm font-medium text-gray-700">
                  {geminiStatus.configured ? 'Configured' : 'Not configured'}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2 text-xs text-gray-500">
                <div>
                  <span className="font-medium">Source:</span>{' '}
                  <span className="capitalize">{geminiStatus.source}</span>
                </div>
                <div>
                  <span className="font-medium">Key:</span> {geminiStatus.maskedKey}
                </div>
                <div>
                  <span className="font-medium">Model:</span> {geminiStatus.model}
                </div>
                <div>
                  <span className="font-medium">Max Tokens:</span> {geminiStatus.maxTokens}
                </div>
                <div>
                  <span className="font-medium">Temperature:</span> {geminiStatus.temperature}
                </div>
              </div>
            </div>

            {/* Update key */}
            {showKeyInput ? (
              <div className="space-y-2">
                <input
                  type="password"
                  value={newApiKey}
                  onChange={(e) => setNewApiKey(e.target.value)}
                  placeholder="Enter new Gemini API key..."
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm
                             focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary"
                />
                <div className="flex items-center gap-2">
                  <Button size="sm" onClick={handleSaveApiKey} loading={savingKey}>
                    Save Key
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setShowKeyInput(false);
                      setNewApiKey('');
                    }}
                  >
                    Cancel
                  </Button>
                </div>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => setShowKeyInput(true)}>
                  {geminiStatus.configured ? 'Change Key' : 'Set Key'}
                </Button>
                {geminiStatus.source === 'database' && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setShowClearKeyConfirm(true)}
                  >
                    Clear (Use Env)
                  </Button>
                )}
              </div>
            )}
          </div>
        )}
      </Card>

      {/* ── System Prompt ────────────────────── */}
      <Card>
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-4">
          <div>
            <h2 className="text-lg font-semibold text-gray-800">AI Chatbot System Prompt</h2>
            <p className="text-xs text-gray-500 mt-0.5">
              The system prompt defines the AI chatbot's behavior, knowledge base, and response
              guidelines. {prompt.length > 0 && `(${prompt.length.toLocaleString()} characters)`}
            </p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {promptChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowResetConfirm(true)}
              disabled={savingPrompt}
            >
              Reset Default
            </Button>
            <Button
              size="sm"
              onClick={handleSavePrompt}
              loading={savingPrompt}
              disabled={!promptChanged}
            >
              Save Prompt
            </Button>
          </div>
        </div>

        {loadingPrompt ? (
          <div className="h-64 bg-gray-100 rounded-lg animate-pulse" />
        ) : (
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            rows={20}
            className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm font-mono
                       leading-relaxed resize-y
                       focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary
                       placeholder:text-gray-400"
            placeholder="Enter system prompt..."
          />
        )}

        <p className="text-xs text-gray-400 mt-2">
          Changes take effect immediately for new chat sessions. Existing conversations will continue
          using the previous prompt until the session ends.
        </p>
      </Card>

      {/* ── Confirm Dialogs ────────────────────── */}
      <ConfirmDialog
        isOpen={showResetConfirm}
        onClose={() => setShowResetConfirm(false)}
        onConfirm={handleResetPrompt}
        title="Reset System Prompt"
        message="This will replace the current system prompt with the default from the application's built-in template. This action cannot be undone."
        confirmLabel="Reset to Default"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showSldResetConfirm}
        onClose={() => setShowSldResetConfirm(false)}
        onConfirm={handleResetSldPrompt}
        title="Reset SLD System Prompt"
        message="This will replace the current SLD prompt with the default from the application's built-in template. This action cannot be undone."
        confirmLabel="Reset to Default"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showClearKeyConfirm}
        onClose={() => setShowClearKeyConfirm(false)}
        onConfirm={handleClearApiKey}
        title="Clear API Key"
        message="This will remove the database-stored API key and revert to the environment variable value. The chatbot will use the environment variable API key."
        confirmLabel="Clear Key"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showDeleteSampleConfirm}
        onClose={() => { setShowDeleteSampleConfirm(false); setDeleteCategoryTarget(''); }}
        onConfirm={handleSampleDelete}
        title="Delete Sample File"
        message="This will permanently remove the sample file. Applicants will no longer be able to view this sample."
        confirmLabel="Delete"
        variant="danger"
      />
    </div>
  );
}
