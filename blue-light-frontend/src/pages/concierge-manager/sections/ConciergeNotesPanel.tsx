/**
 * ConciergeNotesPanel
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - 노트 추가 폼 + 기존 노트 목록 (최신순, Backend 정렬)
 */

import { useState, type FormEvent } from 'react';
import { Button } from '../../../components/ui/Button';
import { Select } from '../../../components/ui/Select';
import type {
  NoteChannel,
  NoteResponse,
} from '../../../api/conciergeManagerApi';

interface Props {
  notes: NoteResponse[];
  onAdd: (channel: NoteChannel, content: string) => Promise<void>;
  disabled?: boolean;
}

const CHANNEL_OPTIONS = [
  { value: 'PHONE', label: 'Phone' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'WHATSAPP', label: 'WhatsApp' },
  { value: 'IN_PERSON', label: 'In person' },
  { value: 'OTHER', label: 'Other' },
];

function fmt(at: string): string {
  try {
    return new Date(at).toLocaleString();
  } catch {
    return at;
  }
}

function channelLabel(ch: NoteChannel): string {
  return CHANNEL_OPTIONS.find((o) => o.value === ch)?.label ?? ch;
}

export function ConciergeNotesPanel({ notes, onAdd, disabled }: Props) {
  const [channel, setChannel] = useState<NoteChannel>('PHONE');
  const [content, setContent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!content.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await onAdd(channel, content.trim());
      setContent('');
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Failed to add note';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4">
      <form onSubmit={handleSubmit} className="space-y-3">
        <Select
          label="Channel"
          name="channel"
          value={channel}
          onChange={(e) => setChannel(e.target.value as NoteChannel)}
          options={CHANNEL_OPTIONS}
          disabled={disabled || submitting}
        />
        <div>
          <label
            htmlFor="note-content"
            className="block text-sm font-medium text-gray-700 mb-1.5"
          >
            Note
          </label>
          <textarea
            id="note-content"
            name="content"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={3}
            maxLength={2000}
            disabled={disabled || submitting}
            placeholder="What happened during this contact?"
            className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
          <p className="mt-1 text-xs text-gray-500">{content.length}/2000</p>
        </div>
        {error && (
          <div
            role="alert"
            className="p-2 rounded bg-error-50 border border-error-200 text-xs text-error-700"
          >
            {error}
          </div>
        )}
        <Button
          type="submit"
          variant="concierge"
          size="sm"
          disabled={disabled || submitting || content.trim().length === 0}
          loading={submitting}
        >
          Add note
        </Button>
      </form>

      <div className="border-t border-gray-200 pt-4">
        {notes.length === 0 ? (
          <p className="text-sm text-gray-500">No notes yet.</p>
        ) : (
          <ul className="space-y-3">
            {notes.map((n) => (
              <li
                key={n.conciergeNoteSeq}
                className="p-3 rounded-md bg-gray-50 border border-gray-100"
              >
                <div className="flex items-center justify-between mb-1 text-xs text-gray-500">
                  <span>
                    <strong className="text-gray-700">{n.authorName}</strong>
                    <span className="mx-1.5">·</span>
                    {channelLabel(n.channel)}
                  </span>
                  <time>{fmt(n.createdAt)}</time>
                </div>
                <p className="text-sm text-gray-800 whitespace-pre-wrap break-words">
                  {n.content}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default ConciergeNotesPanel;
