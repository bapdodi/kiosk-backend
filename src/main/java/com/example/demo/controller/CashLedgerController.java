package com.example.demo.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.entity.CashLedgerEntry;
import com.example.demo.repository.CashLedgerEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 금전출납부 API. 경로에 /admin/ 이 들어가야 SecurityConfig 의 ADMIN 규칙이 걸린다.
 * 잔고는 내려주지 않는다 - 화면이 줄 순서대로 누적한다.
 */
@RestController
@RequestMapping("/api/cash-ledger/admin")
@RequiredArgsConstructor
public class CashLedgerController {

    private final CashLedgerEntryRepository repository;

    public record EntryRequest(LocalDate entryDate, String memo, Long cashIn, Long cardIn, Long expense, String etc) {}

    @GetMapping
    public List<CashLedgerEntry> list() {
        return repository.findAllByOrderByEntryDateAscIdAsc();
    }

    @PostMapping
    public ResponseEntity<CashLedgerEntry> create(@RequestBody EntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(apply(new CashLedgerEntry(), req)));
    }

    @PutMapping("/{id}")
    public CashLedgerEntry update(@PathVariable Long id, @RequestBody EntryRequest req) {
        CashLedgerEntry entry = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이미 삭제된 줄입니다."));
        return repository.save(apply(entry, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (repository.existsById(id)) repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static CashLedgerEntry apply(CashLedgerEntry e, EntryRequest req) {
        if (req.entryDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일자를 입력해 주세요.");
        e.setEntryDate(req.entryDate());
        e.setMemo(text(req.memo()));
        e.setEtc(text(req.etc()));
        e.setCashIn(amount(req.cashIn()));
        e.setCardIn(amount(req.cardIn()));
        e.setExpense(amount(req.expense()));
        return e;
    }

    private static String text(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용은 200자까지 입력할 수 있습니다.");
        return t;
    }

    /** 금액은 0 이상. 합계가 long 을 넘지 않게 상한도 둔다(1조 원). */
    private static long amount(Long v) {
        long n = v == null ? 0 : v;
        if (n < 0 || n > 1_000_000_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "금액은 0 이상 1조 이하로 입력해 주세요.");
        return n;
    }
}
