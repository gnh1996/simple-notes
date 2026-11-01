/**
 * Vditor 表格列宽拖拽（会话级：只影响当前文档的渲染，不写回 Markdown）。
 *
 * admin / desktop 共用；配套样式在 `./vditor.css`（两端都在入口 CSS 里引用）。
 *
 * 实现约束（均已实测，改动前请先复核）：
 * - 宽度只写在 table 与首行单元格的 inline style 上（`table-layout: fixed` + `width`）。
 *   Lute 的 IR → Markdown 序列化会忽略这些样式，正文不会被污染；
 *   **不能插入 `<colgroup>`**：Lute 解析失败会把整张表格从 getValue() 里丢掉。
 * - Vditor 在增删行列 / 单元格内回车 / Tab / 撤销重做时会用 Lute 重建表格 DOM，
 *   所以用 MutationObserver 在重建后重新套用宽度。
 * - 宽度按百分比存，窗口缩放时比例不变；切换文档（外部 setValue）时 reset。
 * - 只存内存：关闭编辑器 / 切换内容即还原，不参与同步，也不写进正文。
 */

/** 命中「列边界」的水平距离（px） */
const EDGE_HIT_PX = 6
/** 单列最小宽度：百分比与像素取较大者 */
const MIN_COLUMN_PERCENT = 5
const MIN_COLUMN_PX = 48
/** 表格数量对不上时的宽限期（覆盖模式切换 / 重渲染的中间态） */
const MISMATCH_GRACE_MS = 1000

export interface TableColumnWidths {
  /** 当前文档内表格的序号（从 0 开始，按渲染顺序） */
  index: number
  /** 每列宽度百分比，总和约等于 100 */
  widths: number[]
}

interface DragState {
  index: number
  column: number
  table: HTMLTableElement
  startX: number
  tableWidth: number
  startWidths: number[]
}

/** 编辑器内容里的表格（只取当前模式可见的表格，排除嵌套表格与渲染预览块里的表格）。 */
function tableList(root: HTMLElement): HTMLTableElement[] {
  return Array.from(root.querySelectorAll<HTMLTableElement>('table')).filter(
    (table) =>
      // Vditor 会同时保留 IR / WYSIWYG 两套容器，未激活的那套是 display:none；
      // 不排除的话切换模式会被误判成「表格数量变了」而丢掉列宽。
      table.getClientRects().length > 0 &&
      !table.parentElement?.closest('table, .vditor-ir__preview, .vditor-wysiwyg__preview'),
  )
}

function headerRow(table: HTMLTableElement): HTMLTableRowElement | null {
  return table.rows.length > 0 ? table.rows[0] : null
}

function simpleHeaderRow(table: HTMLTableElement): HTMLTableRowElement | null {
  const row = headerRow(table)
  if (!row) {
    return null
  }
  return Array.from(row.cells).every((cell) => cell.colSpan === 1) ? row : null
}

/** 按当前渲染结果量出各列占比（百分比，归一化到 100）。 */
function measure(table: HTMLTableElement): number[] | null {
  const row = simpleHeaderRow(table)
  const total = table.getBoundingClientRect().width
  if (!row || total <= 0) {
    return null
  }
  const widths = Array.from(row.cells, (cell) => (cell.getBoundingClientRect().width / total) * 100)
  const sum = widths.reduce((acc, value) => acc + value, 0)
  return sum > 0 ? widths.map((value) => (value / sum) * 100) : null
}

function cellFrom(target: EventTarget | null): HTMLTableCellElement | null {
  return target instanceof Element ? target.closest<HTMLTableCellElement>('th, td') : null
}

export class TableColumnResize {
  private readonly root: HTMLElement
  /** 表格序号 → 各列宽度百分比 */
  private readonly widths = new Map<number, number[]>()
  /** 已套用宽度的表格元素（Vditor 重建后是新元素，因此会重新套用） */
  private readonly painted = new WeakMap<HTMLTableElement, number[]>()
  private readonly observer: MutationObserver
  private drag: DragState | null = null
  private hovered: HTMLTableCellElement | null = null
  /** 记录列宽时文档里的表格数量；数量变化说明序号已错位，直接放弃 */
  private tableCount = 0
  private mismatchTimer: number | undefined

  constructor(root: HTMLElement) {
    this.root = root
    root.addEventListener('pointerdown', this.onPointerDown, true)
    root.addEventListener('pointermove', this.onPointerMove, true)
    root.addEventListener('pointerleave', this.onPointerLeave, true)
    this.observer = new MutationObserver(() => this.restore())
    this.observer.observe(root, { childList: true, subtree: true })
  }

  /** 供将来的 PDF 导出复用：按表格序号取出当前列宽百分比。 */
  getWidths(): TableColumnWidths[] {
    return Array.from(this.widths, ([index, widths]) => ({ index, widths: [...widths] }))
  }

  /** 外部整体替换文档内容时清空（切换笔记 / 冲突处理 / 重新打开文件）。 */
  reset(): void {
    this.cancelMismatchCheck()
    this.finishDrag()
    this.clearHover()
    this.widths.clear()
    this.tableCount = 0
    for (const table of tableList(this.root)) {
      this.clearPaint(table)
    }
  }

  destroy(): void {
    this.observer.disconnect()
    this.cancelMismatchCheck()
    this.finishDrag()
    this.clearHover()
    this.root.removeEventListener('pointerdown', this.onPointerDown, true)
    this.root.removeEventListener('pointermove', this.onPointerMove, true)
    this.root.removeEventListener('pointerleave', this.onPointerLeave, true)
  }

  private paint(table: HTMLTableElement, widths: number[]): void {
    const row = simpleHeaderRow(table)
    if (!row || row.cells.length !== widths.length) {
      return
    }
    table.style.tableLayout = 'fixed'
    Array.from(row.cells).forEach((cell, index) => {
      cell.style.width = `${widths[index].toFixed(2)}%`
    })
    // 表格样式里的 min-width: 5em 会把固定布局重新撑开，按表格粒度关掉
    for (const tableRow of Array.from(table.rows)) {
      for (const cell of Array.from(tableRow.cells)) {
        cell.style.minWidth = '0'
      }
    }
    this.painted.set(table, widths)
  }

  private clearPaint(table: HTMLTableElement): void {
    if (!this.painted.has(table)) {
      return
    }
    this.painted.delete(table)
    table.style.tableLayout = ''
    for (const tableRow of Array.from(table.rows)) {
      for (const cell of Array.from(tableRow.cells)) {
        cell.style.width = ''
        cell.style.minWidth = ''
      }
    }
  }

  /** Vditor 重建表格后把列宽补回去。 */
  private restore(): void {
    if (this.widths.size === 0) {
      return
    }
    const tables = tableList(this.root)
    if (tables.length !== this.tableCount) {
      // 模式切换（IR ↔ SV / WYSIWYG）时表格会短暂消失或两套容器同时可见，
      // 不能立刻判定为「表格增删」：给一个宽限期，恢复后自动重新套用。
      this.scheduleMismatchCheck()
      return
    }
    this.cancelMismatchCheck()
    tables.forEach((table, index) => {
      const widths = this.widths.get(index)
      if (!widths || this.painted.get(table) === widths) {
        return
      }
      if (headerRow(table)?.cells.length !== widths.length) {
        this.widths.delete(index)
        return
      }
      this.paint(table, widths)
    })
  }

  private scheduleMismatchCheck(): void {
    if (this.mismatchTimer !== undefined) {
      return
    }
    this.mismatchTimer = window.setTimeout(() => {
      this.mismatchTimer = undefined
      if (this.widths.size === 0) {
        return
      }
      if (tableList(this.root).length !== this.tableCount) {
        // 宽限期结束仍然对不上：文档确实增删了表格，序号已错位，放弃列宽
        this.widths.clear()
        this.tableCount = 0
        return
      }
      this.restore()
    }, MISMATCH_GRACE_MS)
  }

  /** 命中表头单元格右边界时返回可拖动的目标。 */
  private hitTest(event: PointerEvent): { table: HTMLTableElement; column: number } | null {
    const cell = cellFrom(event.target)
    const row = cell?.parentElement instanceof HTMLTableRowElement ? cell.parentElement : null
    if (!cell || !cell.isContentEditable || !row || row.rowIndex !== 0) {
      return null
    }
    const table = cell.closest('table')
    if (!(table instanceof HTMLTableElement) || !this.root.contains(table)) {
      return null
    }
    const header = simpleHeaderRow(table)
    if (!header) {
      return null
    }
    const rect = cell.getBoundingClientRect()
    if (Math.abs(event.clientX - rect.right) > EDGE_HIT_PX) {
      return null
    }
    const column = cell.cellIndex
    // 最后一列右侧是表格边界，没有可交换宽度的邻列
    if (column < 0 || column >= header.cells.length - 1) {
      return null
    }
    return { table, column }
  }

  private onPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0 || this.drag) {
      return
    }
    const hit = this.hitTest(event)
    if (!hit) {
      return
    }
    const widths = measure(hit.table)
    if (!widths) {
      return
    }
    const tables = tableList(this.root)
    const index = tables.indexOf(hit.table)
    if (index < 0) {
      return
    }
    event.preventDefault()
    event.stopPropagation()
    this.clearHover()
    this.cancelMismatchCheck()
    this.tableCount = tables.length
    this.widths.set(index, widths)
    this.paint(hit.table, widths)
    this.drag = {
      index,
      column: hit.column,
      table: hit.table,
      startX: event.clientX,
      tableWidth: hit.table.getBoundingClientRect().width,
      startWidths: widths,
    }
    document.addEventListener('pointermove', this.onDragMove, true)
    document.addEventListener('pointerup', this.onDragEnd, true)
    document.addEventListener('pointercancel', this.onDragEnd, true)
    window.addEventListener('blur', this.onDragEnd)
    document.body.classList.add('sn-col-resizing')
  }

  private onDragMove = (event: PointerEvent): void => {
    const drag = this.drag
    if (!drag) {
      return
    }
    event.preventDefault()
    const minPercent = Math.max(MIN_COLUMN_PERCENT, (MIN_COLUMN_PX / drag.tableWidth) * 100)
    const pair = drag.startWidths[drag.column] + drag.startWidths[drag.column + 1]
    if (pair <= minPercent * 2) {
      return
    }
    const delta = ((event.clientX - drag.startX) / drag.tableWidth) * 100
    const next = Math.min(Math.max(drag.startWidths[drag.column] + delta, minPercent), pair - minPercent)
    const widths = [...drag.startWidths]
    widths[drag.column] = next
    widths[drag.column + 1] = pair - next
    this.widths.set(drag.index, widths)
    this.paint(drag.table, widths)
  }

  private onDragEnd = (): void => {
    if (!this.drag) {
      return
    }
    this.finishDrag()
  }

  private finishDrag(): void {
    if (!this.drag) {
      return
    }
    this.drag = null
    document.removeEventListener('pointermove', this.onDragMove, true)
    document.removeEventListener('pointerup', this.onDragEnd, true)
    document.removeEventListener('pointercancel', this.onDragEnd, true)
    window.removeEventListener('blur', this.onDragEnd)
    document.body.classList.remove('sn-col-resizing')
  }

  private onPointerMove = (event: PointerEvent): void => {
    if (this.drag) {
      return
    }
    this.updateHover(this.hitTest(event) ? cellFrom(event.target) : null)
  }

  private onPointerLeave = (): void => {
    if (!this.drag) {
      this.clearHover()
    }
  }

  private cancelMismatchCheck(): void {
    if (this.mismatchTimer !== undefined) {
      window.clearTimeout(this.mismatchTimer)
      this.mismatchTimer = undefined
    }
  }

  private updateHover(cell: HTMLTableCellElement | null): void {
    if (this.hovered === cell) {
      return
    }
    this.clearHover()
    this.hovered = cell
    if (cell) {
      cell.style.cursor = 'col-resize'
    }
  }

  private clearHover(): void {
    if (this.hovered) {
      this.hovered.style.cursor = ''
      this.hovered = null
    }
  }
}
