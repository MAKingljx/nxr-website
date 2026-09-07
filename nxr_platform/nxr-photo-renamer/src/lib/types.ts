export type ScanState =
  | "pending"
  | "scanning"
  | "found"
  | "none"
  | "error"
  | "ambiguous";

export interface Photo {
  id: string;
  name: string;
  file: File;
  handle?: FileSystemFileHandle;
  thumbnailUrl: string;
  scanState: ScanState;
  certIds: string[];
  qrTexts: string[];
  error?: string;
  textReference?: TextReference;
}

export interface TextReference {
  state: "matched" | "mismatch" | "unreadable" | "unavailable" | "reference";
  candidates: string[];
  rawText: string;
  error?: string;
}

export interface Pair {
  id: string;
  frontId: string;
  backId: string;
  certId: string;
  selected: boolean;
  manual?: boolean;
}

export interface RenameRequest {
  sourceName: string;
  targetName: string;
  expectedSize: number;
  expectedLastModified: number;
  expectedHash?: string;
  outputFormat?: "webp-lossless";
}
