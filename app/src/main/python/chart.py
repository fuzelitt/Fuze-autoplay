from judge_line import JudgeLine


class Chart:
    version: int
    offset: float
    notes_count: int
    judge_lines: list[JudgeLine]

    def __init__(self, version: int, offset: float, notes_count: int, judge_lines: list[JudgeLine]):
        self.version = version
        self.offset = offset
        self.notes_count = notes_count
        self.judge_lines = judge_lines

    @classmethod
    def from_dict(cls, d: dict):
        # Some chart files omit formatVersion / offset / numOfNotes; they are not needed for planning.
        version = d.get('formatVersion', 3)
        offset = d.get('offset', 0.0)
        if version == 1:
            lines = [*map(JudgeLine.from_dict_v1, d['judgeLineList'])]
        else:
            lines = [*map(JudgeLine.from_dict, d['judgeLineList'])]
        count = d.get('numOfNotes')
        if count is None:
            count = sum(len(l.notes_above) + len(l.notes_below) for l in lines)
        return cls(version, offset, count, lines)


__all__ = ['Chart']
