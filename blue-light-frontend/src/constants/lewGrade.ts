// LEW 등급 옵션 단일 소스(SSOT). 백엔드 LewGrade enum(GRADE_7/8/9)과 동기화.
// 등급은 EMA 법적 자격 등급(3종 고정)이라 하드코딩 허용 — 단, 한 곳에서만 정의해
// 셋업/가입/프로필/admin 화면이 공유한다. (CLAUDE.md "설정 우선" — 분산 하드코딩 금지)

export const LEW_GRADES = [
  { value: 'GRADE_7', label: 'Grade 7', desc: '≤ 45 kVA' },
  { value: 'GRADE_8', label: 'Grade 8', desc: '≤ 500 kVA' },
  { value: 'GRADE_9', label: 'Grade 9', desc: '≤ 400 kV' },
] as const;

export type LewGradeValue = (typeof LEW_GRADES)[number]['value'];

/** Select 옵션용 — "Grade 7 (≤ 45 kVA)" 형태 라벨. */
export const LEW_GRADE_SELECT_OPTIONS = LEW_GRADES.map((g) => ({
  value: g.value,
  label: `${g.label} (${g.desc})`,
}));
