-- 미사용 옵션 그룹 기능 제거. 다른 환경에 데이터가 있으면 보존하고 적용을 중단한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.option_groups)
       OR EXISTS (SELECT 1 FROM public.option_group_values) THEN
        RAISE EXCEPTION '옵션 그룹 데이터가 남아 있습니다. 규격으로 이전한 뒤 적용하세요.';
    END IF;
END $$;

DROP TABLE public.option_group_values;
DROP TABLE public.option_groups;
