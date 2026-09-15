/** The authenticated user's context, as returned by GET /api/v1/me (mirrors CurrentUserDto). */
export interface CurrentUser {
  userId: string
  email: string
  fullName: string
  organizationId: string | null
  organizationName: string | null
  roles: string[]
}

/** The backend's single error shape: { code, message, correlationId, details }. */
export interface ApiError {
  code: string
  message: string
  correlationId?: string
  details?: unknown
}

export type PatientStatus = 'ACTIVE' | 'INACTIVE'

/** A patient profile, as returned by the patients API (mirrors PatientDto). */
export interface Patient {
  id: string
  medicalRecordNumber: string
  fullName: string
  // Consent-controlled (§23): null when the backend masked it for this caller; the field name then
  // appears in `maskedFields`. Never trust the client to hide it — the backend omits the value.
  dateOfBirth: string | null // ISO date (yyyy-mm-dd), or null when masked
  status: PatientStatus
  version: number
  maskedFields?: string[]
}

/** Payload to create a patient. The tenant is stamped on the server, never sent by the client. */
export interface PatientCreateRequest {
  medicalRecordNumber: string
  fullName: string
  dateOfBirth: string // ISO date (yyyy-mm-dd)
}

export type ServiceRequestStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'TRIAGED'
  | 'ASSIGNED'
  | 'UNDER_REVIEW'
  | 'NEEDS_INFORMATION'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'CLOSED'

export type ServiceRequestType =
  | 'CLAIM_SUPPORT'
  | 'REFERRAL_REQUEST'
  | 'DOCUMENT_REVIEW'
  | 'APPOINTMENT_HELP'
  | 'BENEFIT_CLARIFICATION'

export type ServiceRequestPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

/** A service request (mirrors ServiceRequestDto). */
export interface ServiceRequest {
  id: string
  patientId: string
  type: ServiceRequestType
  status: ServiceRequestStatus
  priority: ServiceRequestPriority
  title: string
  description: string | null
  createdBy: string
  version: number
  createdAt: string
}

/** One status-history entry (mirrors RequestStatusHistoryDto). */
export interface RequestStatusHistory {
  id: string
  fromStatus: ServiceRequestStatus | null
  toStatus: ServiceRequestStatus
  actorUserId: string
  reason: string | null
  createdAt: string
}

/** Payload to create a request. Tenant + creator are set on the server. */
export interface ServiceRequestCreateRequest {
  patientId: string
  type: ServiceRequestType
  priority?: ServiceRequestPriority
  title: string
  description?: string
}

/** Payload to apply a controlled status transition (optimistic-locked). */
export interface StatusChangeRequest {
  targetStatus: ServiceRequestStatus
  expectedVersion: number
  reason?: string
}

/** One comment on a request (mirrors RequestCommentDto). */
export interface RequestComment {
  id: string
  authorUserId: string
  body: string
  createdAt: string
}

/** Payload to add a comment. Tenant, request, and author are set on the server. */
export interface AddCommentRequest {
  body: string
}

/** The current assignment on a request (mirrors RequestAssignmentDto); null when unassigned. */
export interface RequestAssignment {
  id: string
  assigneeUserId: string
  assigneeName: string
  assigneeRole: string
  assignedByUserId: string
  assignedAt: string
}

/** A candidate assignee — a same-tenant provider or claims reviewer (mirrors AssignableUserDto). */
export interface AssignableUser {
  userId: string
  fullName: string
  role: string
}

/** Payload to assign/reassign a request. Tenant + assigner are set on the server (optimistic-locked). */
export interface AssignRequest {
  assigneeUserId: string
  expectedVersion: number
}

// --- Consent (§22) --------------------------------------------------------

export type ConsentEffect = 'GRANT' | 'DENY'

export type ConsentPurpose =
  | 'CARE_COORDINATION'
  | 'CLAIM_PROCESSING'
  | 'DOCUMENT_REVIEW'
  | 'APPOINTMENT_SUPPORT'
  | 'BENEFIT_SUPPORT'

export type ConsentDataCategory =
  | 'DEMOGRAPHICS_CONTACT'
  | 'CARE_COORDINATION'
  | 'CLINICAL_CONTEXT'
  | 'CLAIMS_BENEFITS'
  | 'DOCUMENTS'

export type ConsentScopeType = 'PROVIDER' | 'CARE_TEAM' | 'ORGANIZATION'

export type ConsentStatus = 'SCHEDULED' | 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'SUPERSEDED'

/** A consent directive (mirrors ConsentDirectiveDto). `expectedVersion` is the optimistic-lock value. */
export interface ConsentDirective {
  id: string
  patientId: string
  directiveGroupId: string
  effect: ConsentEffect
  purpose: ConsentPurpose
  dataCategory: ConsentDataCategory
  scopeType: ConsentScopeType
  scopeRefId: string | null
  effectiveFrom: string // ISO date
  effectiveTo: string | null
  status: ConsentStatus
  version: number
  expectedVersion: number
  createdAt: string
  endedAt: string | null
}

/**
 * Payload to record a consent directive. `scopeRefId` is required only for PROVIDER scope (the provider's
 * user id) and must be absent otherwise; `effectiveFrom`/`To` are optional (default today / open-ended).
 */
export interface RecordConsentRequest {
  effect: ConsentEffect
  purpose: ConsentPurpose
  dataCategory: ConsentDataCategory
  scopeType: ConsentScopeType
  scopeRefId?: string
  effectiveFrom?: string
  effectiveTo?: string
}

/** The lifecycle states shared by both care-team assignment tables. */
export type AssignmentStatus = 'PENDING' | 'ACTIVE' | 'EXPIRED' | 'REVOKED'

/** A provider-patient assignment (mirrors ProviderPatientAssignmentDto) — used to pick a scoped provider. */
export interface ProviderAssignment {
  id: string
  patientId: string
  providerUserId: string
  providerName: string
  assignedByUserId: string
  status: AssignmentStatus
  effectiveFrom: string
  effectiveTo: string | null
  expectedVersion: number
  assignedAt: string
  endedAt: string | null
}

/** A care-coordinator↔patient assignment (mirrors CareCoordinatorAssignmentDto). */
export interface CoordinatorAssignment {
  id: string
  patientId: string
  coordinatorUserId: string
  coordinatorName: string
  assignedByUserId: string
  status: AssignmentStatus
  effectiveFrom: string
  effectiveTo: string | null
  expectedVersion: number
  assignedAt: string
  endedAt: string | null
}

/** A user who can be newly assigned to a patient's care team (mirrors AssignmentCandidateDto). */
export interface AssignmentCandidate {
  userId: string
  fullName: string
}

/**
 * Payload to assign a member to a patient's care team. `userId` is the provider/coordinator to add;
 * `effectiveFrom`/`To` are optional (default today / open-ended). Tenant + assigner are set on the server.
 */
export interface AssignMemberRequest {
  userId: string
  effectiveFrom?: string
  effectiveTo?: string
}

// --- Documents (§19) ------------------------------------------------------

/** The malware-scan lifecycle of a document. Only CLEAN documents can be downloaded. */
export type DocumentScanStatus = 'PENDING' | 'CLEAN' | 'QUARANTINED'

/** Metadata for a patient document (mirrors DocumentDto) — the bytes are fetched separately. */
export interface PatientDocument {
  id: string
  patientId: string
  fileName: string
  contentType: string
  sizeBytes: number
  scanStatus: DocumentScanStatus
  uploadedByUserId: string
  uploadedAt: string
}

// --- Claims (§Phase 4) ----------------------------------------------------

/** The claim lifecycle. ADJUDICATED is reached only by the adjudication engine, never a bare status change. */
export type ClaimStatus = 'DRAFT' | 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'ADJUDICATED' | 'CANCELLED'

/** The medical-code systems a claim line can reference (procedures are CPT/HCPCS). */
export type CodeSystem = 'ICD10CM' | 'HCPCS' | 'CPT'

/** One billed line of a claim (mirrors ClaimLineDto). Money is a plain number (NUMERIC → JSON number). */
export interface ClaimLine {
  id: string
  lineNumber: number
  procedureCodeSystem: CodeSystem
  procedureCode: string
  units: number
  chargeAmount: number
}

/** A claim header row for the list/work queue (mirrors ClaimSummaryDto). */
export interface ClaimSummary {
  id: string
  patientId: string
  claimNumber: string
  status: ClaimStatus
  serviceDate: string // ISO date
  totalChargeAmount: number
  createdAt: string
}

/** A claim aggregate — header plus lines (mirrors ClaimDto). */
export interface Claim {
  id: string
  patientId: string
  claimNumber: string
  status: ClaimStatus
  serviceDate: string
  totalChargeAmount: number
  createdBy: string
  createdAt: string
  version: number
  lines: ClaimLine[]
}

/** One claim status-history entry (mirrors ClaimStatusHistoryDto). */
export interface ClaimStatusHistory {
  id: string
  fromStatus: ClaimStatus | null
  toStatus: ClaimStatus
  actorUserId: string
  reason: string | null
  createdAt: string
}

/** Payload to apply a controlled claim status transition (optimistic-locked). */
export interface ClaimStatusChange {
  targetStatus: ClaimStatus
  expectedVersion: number
  reason?: string
}

// --- Adjudication (§Phase 5) ---------------------------------------------

/** The claim-level adjudication outcome. */
export type AdjudicationOutcome = 'ADJUDICATED' | 'DENIED_NO_ELIGIBILITY'

/** The per-line adjudication outcome. */
export type LineOutcome = 'COVERED' | 'NOT_COVERED'

/** The explainable per-line breakdown (mirrors AdjudicationLineDto). All amounts are numbers. */
export interface AdjudicationLine {
  claimLineId: string
  lineNumber: number
  procedureCodeSystem: CodeSystem
  procedureCode: string
  outcome: LineOutcome
  chargeAmount: number
  allowedAmount: number
  copayAmount: number
  deductibleAppliedAmount: number
  coinsuranceAmount: number
  oopMaxAppliedAmount: number
  planPaidAmount: number
  memberResponsibility: number
}

/** A claim's adjudication — the plan that applied, the totals, and the per-line breakdown (mirrors AdjudicationDto). */
export interface Adjudication {
  id: string
  claimId: string
  adjudicationVersion: number
  outcome: AdjudicationOutcome
  coveragePlanId: string | null
  coveragePlanName: string | null
  eligibilityId: string | null
  totalChargeAmount: number
  totalAllowedAmount: number
  totalPlanPaidAmount: number
  totalMemberResponsibility: number
  adjudicatedBy: string
  adjudicatedAt: string
  lines: AdjudicationLine[]
}

// --- Medical codes (§Phase 4) + claim creation ---------------------------

/** A medical code from the global catalog (mirrors MedicalCodeDto). category is 'Diagnosis' or 'Procedure'. */
export interface MedicalCode {
  id: string
  codeSystem: CodeSystem
  systemLabel: string
  category: string
  code: string
  description: string
}

/** One line to bill on a new claim. The backend resolves the code's system (CPT/HCPCS) and validates it. */
export interface CreateClaimLine {
  procedureCode: string
  units?: number
  chargeAmount: number
}

/** Payload to create a DRAFT claim. Tenant + claim number are set on the server; serviceDate is past-or-present. */
export interface CreateClaimRequest {
  patientId: string
  serviceDate: string
  lines: CreateClaimLine[]
}

// --- Coverage plans + exclusions (§Phase 4/5) ----------------------------

/** A benefit plan type. */
export type PlanType = 'HMO' | 'PPO' | 'EPO' | 'HDHP'

/** A coverage plan (mirrors CoveragePlanDto). coinsuranceRate is a 0..1 fraction; outOfPocketMax may be null. */
export interface CoveragePlan {
  id: string
  planCode: string
  name: string
  planType: PlanType
  deductibleAmount: number
  coinsuranceRate: number
  copayAmount: number
  outOfPocketMax: number | null
  active: boolean
  version: number
}

/** Payload to create a coverage plan (ORG_ADMIN). Tenant is set on the server; planCode unique per tenant. */
export interface CreateCoveragePlanRequest {
  planCode: string
  name: string
  planType: PlanType
  deductibleAmount: number
  coinsuranceRate: number
  copayAmount: number
  outOfPocketMax?: number
}

/** A patient's enrollment in a coverage plan for an effective-dated period (mirrors PatientEligibilityDto). */
export interface PatientEligibility {
  id: string
  patientId: string
  coveragePlanId: string
  coveragePlanName: string
  memberId: string
  effectiveFrom: string
  effectiveTo: string | null
  version: number
}

/**
 * Payload to enroll a patient in a coverage plan (CARE_COORDINATOR/ORG_ADMIN). Tenant + patient come from the
 * path/context; the plan must be in-tenant (else 400) and the period must not overlap an existing one (else 409).
 */
export interface EnrollEligibilityRequest {
  coveragePlanId: string
  memberId: string
  effectiveFrom: string
  effectiveTo?: string
}

/** A procedure a plan excludes (mirrors PlanExclusionDto). */
export interface PlanExclusion {
  id: string
  coveragePlanId: string
  codeSystem: CodeSystem
  code: string
}

/** Payload to exclude a procedure from a plan (ORG_ADMIN); the backend resolves + validates the code. */
export interface AddPlanExclusionRequest {
  procedureCode: string
}
