import { useState, useEffect, useCallback } from 'react';
import { Card, CardHeader } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { Pagination } from '../../components/data/Pagination';
import { useToastStore } from '../../stores/toastStore';
import * as api from '../../api/dataBreachApi';
import type { DataBreach, DataBreachRequest } from '../../api/dataBreachApi';
import type { Page } from '../../types';
import type { BadgeVariant } from '../../components/ui/Badge';

const SEVERITY_BADGES: Record<string, BadgeVariant> = {
  CRITICAL: 'error',
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'gray',
};

const STATUS_BADGES: Record<string, BadgeVariant> = {
  DETECTED: 'error',
  INVESTIGATING: 'warning',
  PDPC_NOTIFIED: 'primary',
  USERS_NOTIFIED: 'primary',
  CONTAINED: 'warning',
  RESOLVED: 'success',
};

export default function DataBreachPage() {
  const toast = useToastStore();
  const [page, setPage] = useState<Page<DataBreach> | null>(null);
  const [loading, setLoading] = useState(true);
  const [currentPage, setCurrentPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [selectedBreach, setSelectedBreach] = useState<DataBreach | null>(null);

  // Form fields
  const [formTitle, setFormTitle] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formSeverity, setFormSeverity] = useState('HIGH');
  const [formAffectedCount, setFormAffectedCount] = useState('0');
  const [formDataTypes, setFormDataTypes] = useState('');
  const [formContainment, setFormContainment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // PDPC notification form
  const [pdpcRefNo, setPdpcRefNo] = useState('');

  const loadBreaches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.getDataBreaches(statusFilter || undefined, currentPage);
      setPage(data);
    } catch {
      toast.error('Failed to load data breach records');
    } finally {
      setLoading(false);
    }
  }, [currentPage, statusFilter]);

  useEffect(() => {
    loadBreaches();
  }, [loadBreaches]);

  const handleSubmitBreach = async () => {
    if (!formTitle.trim() || !formDescription.trim()) {
      toast.error('Title and description are required');
      return;
    }
    setSubmitting(true);
    try {
      const request: DataBreachRequest = {
        title: formTitle.trim(),
        description: formDescription.trim(),
        severity: formSeverity,
        affectedCount: parseInt(formAffectedCount) || 0,
        dataTypesAffected: formDataTypes.trim() || undefined,
        containmentActions: formContainment.trim() || undefined,
      };
      await api.reportDataBreach(request);
      toast.success('Data breach reported successfully');
      resetForm();
      loadBreaches();
    } catch {
      toast.error('Failed to report data breach');
    } finally {
      setSubmitting(false);
    }
  };

  const resetForm = () => {
    setShowForm(false);
    setFormTitle('');
    setFormDescription('');
    setFormSeverity('HIGH');
    setFormAffectedCount('0');
    setFormDataTypes('');
    setFormContainment('');
  };

  const handleNotifyPdpc = async (breachSeq: number) => {
    try {
      const updated = await api.notifyPdpc(breachSeq, pdpcRefNo);
      toast.success('PDPC notification recorded');
      setPdpcRefNo('');
      if (selectedBreach?.breachSeq === breachSeq) setSelectedBreach(updated);
      loadBreaches();
    } catch {
      toast.error('Failed to record PDPC notification');
    }
  };

  const handleNotifyUsers = async (breachSeq: number) => {
    try {
      const updated = await api.notifyUsers(breachSeq);
      toast.success('User notification recorded');
      if (selectedBreach?.breachSeq === breachSeq) setSelectedBreach(updated);
      loadBreaches();
    } catch {
      toast.error('Failed to record user notification');
    }
  };

  const handleResolve = async (breachSeq: number) => {
    try {
      const updated = await api.resolveBreach(breachSeq);
      toast.success('Breach marked as resolved');
      if (selectedBreach?.breachSeq === breachSeq) setSelectedBreach(updated);
      loadBreaches();
    } catch {
      toast.error('Failed to resolve breach');
    }
  };

  const formatDate = (d?: string) => d ? new Date(d).toLocaleString() : '-';

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Data Breach Management</h1>
          <p className="text-sm text-gray-500 mt-1">PDPA compliance &mdash; Track and manage data breach notifications</p>
        </div>
        <Button onClick={() => setShowForm(!showForm)}>
          {showForm ? 'Cancel' : 'Report New Breach'}
        </Button>
      </div>

      {/* Report form */}
      {showForm && (
        <Card>
          <CardHeader title="Report Data Breach" description="Document a new data breach incident" />
          <div className="space-y-4">
            <Input label="Title" value={formTitle} onChange={(e) => setFormTitle(e.target.value)} required
              placeholder="Brief description of the breach" />
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Description *</label>
              <textarea value={formDescription} onChange={(e) => setFormDescription(e.target.value)}
                rows={3} placeholder="Detailed description of the breach, how it was discovered, and potential impact"
                className="w-full rounded-xl border border-gray-200 px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary" />
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <Select label="Severity" value={formSeverity} onChange={(e) => setFormSeverity(e.target.value)}
                options={[
                  { value: 'LOW', label: 'Low' },
                  { value: 'MEDIUM', label: 'Medium' },
                  { value: 'HIGH', label: 'High' },
                  { value: 'CRITICAL', label: 'Critical' },
                ]} />
              <Input label="Affected Users" type="number" value={formAffectedCount}
                onChange={(e) => setFormAffectedCount(e.target.value)} placeholder="0" />
              <Input label="Affected Data Types" value={formDataTypes}
                onChange={(e) => setFormDataTypes(e.target.value)} placeholder="e.g., Email, Phone, Address" />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Containment Actions</label>
              <textarea value={formContainment} onChange={(e) => setFormContainment(e.target.value)}
                rows={2} placeholder="Actions taken to contain the breach"
                className="w-full rounded-xl border border-gray-200 px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary" />
            </div>
            <div className="flex gap-3">
              <Button onClick={handleSubmitBreach} loading={submitting}>Submit Report</Button>
              <Button variant="outline" onClick={resetForm}>Cancel</Button>
            </div>
          </div>
        </Card>
      )}

      {/* Filters */}
      <div className="flex gap-3 items-end">
        <Select label="Status" value={statusFilter} onChange={(e) => { setStatusFilter(e.target.value); setCurrentPage(0); }}
          options={[
            { value: '', label: 'All Statuses' },
            { value: 'DETECTED', label: 'Detected' },
            { value: 'INVESTIGATING', label: 'Investigating' },
            { value: 'PDPC_NOTIFIED', label: 'PDPC Notified' },
            { value: 'USERS_NOTIFIED', label: 'Users Notified' },
            { value: 'CONTAINED', label: 'Contained' },
            { value: 'RESOLVED', label: 'Resolved' },
          ]} />
      </div>

      {/* Breach list */}
      {loading ? (
        <div className="flex justify-center py-12">
          <LoadingSpinner size="lg" label="Loading breach records..." />
        </div>
      ) : page && page.content.length > 0 ? (
        <>
          <div className="space-y-3">
            {page.content.map((breach) => (
              <Card key={breach.breachSeq}>
                <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3 className="text-sm font-semibold text-gray-800">{breach.title}</h3>
                      <Badge variant={SEVERITY_BADGES[breach.severity] || 'gray'}>
                        {breach.severity}
                      </Badge>
                      <Badge variant={STATUS_BADGES[breach.status] || 'gray'}>
                        {breach.status.replace(/_/g, ' ')}
                      </Badge>
                      {breach.pdpcOverdue && (
                        <Badge variant="error">PDPC OVERDUE</Badge>
                      )}
                    </div>
                    <p className="text-xs text-gray-500 mt-1">
                      Reported: {formatDate(breach.createdAt)} | Affected: {breach.affectedCount} users
                      {breach.dataTypesAffected && ` | Data: ${breach.dataTypesAffected}`}
                    </p>
                    <p className="text-sm text-gray-600 mt-2 line-clamp-2">{breach.description}</p>
                  </div>
                  <div className="flex-shrink-0">
                    <Button variant="outline" onClick={() => setSelectedBreach(
                      selectedBreach?.breachSeq === breach.breachSeq ? null : breach
                    )}>
                      {selectedBreach?.breachSeq === breach.breachSeq ? 'Close' : 'Manage'}
                    </Button>
                  </div>
                </div>

                {/* Expanded management panel */}
                {selectedBreach?.breachSeq === breach.breachSeq && (
                  <div className="mt-4 pt-4 border-t border-gray-100 space-y-4">
                    {/* Timeline */}
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
                      <div>
                        <span className="text-xs text-gray-500">PDPC Notified</span>
                        <p className="font-medium text-gray-700">
                          {breach.pdpcNotifiedAt ? formatDate(breach.pdpcNotifiedAt) : 'Not yet'}
                          {breach.pdpcReferenceNo && ` (Ref: ${breach.pdpcReferenceNo})`}
                        </p>
                      </div>
                      <div>
                        <span className="text-xs text-gray-500">Users Notified</span>
                        <p className="font-medium text-gray-700">
                          {breach.usersNotifiedAt ? formatDate(breach.usersNotifiedAt) : 'Not yet'}
                        </p>
                      </div>
                      <div>
                        <span className="text-xs text-gray-500">Resolved</span>
                        <p className="font-medium text-gray-700">
                          {breach.resolvedAt ? formatDate(breach.resolvedAt) : 'Not resolved'}
                        </p>
                      </div>
                      <div>
                        <span className="text-xs text-gray-500">Containment Actions</span>
                        <p className="font-medium text-gray-700">
                          {breach.containmentActions || 'None recorded'}
                        </p>
                      </div>
                    </div>

                    {/* Action buttons */}
                    <div className="flex flex-wrap gap-2 pt-2">
                      {!breach.pdpcNotifiedAt && (
                        <div className="flex items-end gap-2">
                          <Input label="PDPC Reference No." value={pdpcRefNo}
                            onChange={(e) => setPdpcRefNo(e.target.value)}
                            placeholder="e.g., PDPC-2026-XXXX" />
                          <Button onClick={() => handleNotifyPdpc(breach.breachSeq)}>
                            Record PDPC Notification
                          </Button>
                        </div>
                      )}
                      {breach.pdpcNotifiedAt && !breach.usersNotifiedAt && (
                        <Button onClick={() => handleNotifyUsers(breach.breachSeq)}>
                          Record User Notification
                        </Button>
                      )}
                      {breach.status !== 'RESOLVED' && (
                        <Button variant="outline" onClick={() => handleResolve(breach.breachSeq)}>
                          Mark as Resolved
                        </Button>
                      )}
                    </div>

                    {/* PDPA reminder */}
                    {breach.pdpcOverdue && (
                      <div className="bg-red-50 rounded-lg p-3 text-sm text-red-700">
                        <strong>Warning:</strong> PDPA requires PDPC notification within 3 calendar days
                        of becoming aware of a notifiable data breach. This breach is overdue.
                      </div>
                    )}
                  </div>
                )}
              </Card>
            ))}
          </div>

          {page.totalPages > 1 && (
            <Pagination
              page={currentPage}
              totalPages={page.totalPages}
              onPageChange={setCurrentPage}
            />
          )}
        </>
      ) : (
        <Card>
          <div className="text-center py-8 text-gray-500">
            <p className="text-sm">No data breach records found</p>
          </div>
        </Card>
      )}

      {/* PDPA Info */}
      <Card>
        <CardHeader title="PDPA Data Breach Notification Requirements" description="Key obligations under the Personal Data Protection Act 2012" />
        <div className="space-y-2 text-sm text-gray-600">
          <p><strong>Timeline:</strong> Notify PDPC within 3 calendar days of determining a breach is notifiable.</p>
          <p><strong>Notifiable Breach:</strong> A breach is notifiable if it results in, or is likely to result in, significant harm to affected individuals, or is of a significant scale (500+ affected individuals).</p>
          <p><strong>User Notification:</strong> Notify affected individuals as soon as practicable if the breach is likely to result in significant harm.</p>
          <p><strong>PDPC Contact:</strong>{' '}
            <a href="https://www.pdpc.gov.sg/help-and-resources/2021/01/data-breach-notification"
              target="_blank" rel="noopener noreferrer" className="text-primary underline">
              PDPC Data Breach Portal
            </a>
          </p>
        </div>
      </Card>
    </div>
  );
}
