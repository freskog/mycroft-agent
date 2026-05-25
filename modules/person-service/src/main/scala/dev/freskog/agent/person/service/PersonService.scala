package dev.freskog.agent.person.service

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._

import zio.{Clock, Task, ZIO}

import java.time.Instant
import java.util.UUID

trait PersonService {
  def createPerson(req: CreatePersonRequest): Task[Person]
  def listPersons: Task[List[Person]]
  def createScope(req: CreateScopeRequest): Task[PersonScope]
  def listScopes: Task[List[PersonScope]]
  def createScopeRole(req: CreateScopeRoleRequest): Task[PersonScopeRole]
  def proposeCommitment(req: ProposeCommitmentRequest): Task[Commitment]
  def listCommitments(owner: Option[String], scope: Option[String], status: Option[String]): Task[List[Commitment]]
  def proposeMemory(req: ProposeMemoryRequest): Task[MemoryItem]
  def listMemory(personId: Option[String], scopeId: Option[String]): Task[List[MemoryItem]]
  def requestApproval(req: RequestApprovalRequest): Task[Approval]
  def listApprovals(scopeId: Option[String], status: Option[String]): Task[List[Approval]]
}

object PersonService {

  def live(
    personRepo: PersonRepo,
    scopeRepo: ScopeRepo,
    scopeRoleRepo: ScopeRoleRepo,
    commitmentRepo: CommitmentRepo,
    memoryRepo: MemoryRepo,
    approvalRepo: ApprovalRepo,
    auditRepo: AuditRepo
  ): PersonService = new PersonService {

    def createPerson(req: CreatePersonRequest): Task[Person] = {
      val person = Person(
        id = req.id,
        displayName = req.displayName,
        timezone = req.timezone,
        defaultLocale = req.defaultLocale,
        active = true
      )
      personRepo.create(person).as(person)
    }

    def listPersons: Task[List[Person]] = personRepo.findAll

    def createScope(req: CreateScopeRequest): Task[PersonScope] = {
      val scope = PersonScope(
        id = req.id,
        name = req.name,
        ownerPersonId = req.ownerPersonId,
        kind = req.kind
      )
      scopeRepo.create(scope).as(scope)
    }

    def listScopes: Task[List[PersonScope]] = scopeRepo.findAll

    def createScopeRole(req: CreateScopeRoleRequest): Task[PersonScopeRole] = {
      val role = PersonScopeRole(
        personId = req.personId,
        scopeId = req.scopeId,
        role = req.role
      )
      scopeRoleRepo.create(role).as(role)
    }

    def proposeCommitment(req: ProposeCommitmentRequest): Task[Commitment] =
      for {
        now <- Clock.instant
        id   = UUID.randomUUID().toString
        commitment = Commitment(
          id = id,
          ownerPersonId = req.ownerPersonId,
          scopeId = req.scopeId,
          status = CommitmentStatus.Proposed,
          text = req.text,
          source = req.source,
          evidence = req.evidence,
          dueAt = req.dueAt,
          createdAt = now,
          updatedAt = now
        )
        _ <- commitmentRepo.create(commitment)
        _ <- auditRepo.create(AuditEvent(
          id = UUID.randomUUID().toString,
          actor = "agent",
          action = "commitment.propose",
          targetType = "commitment",
          targetId = Some(id),
          scopeId = Some(req.scopeId),
          payloadJson = "{}",
          createdAt = now
        ))
      } yield commitment

    def listCommitments(owner: Option[String], scope: Option[String], status: Option[String]): Task[List[Commitment]] =
      commitmentRepo.findAll(owner, scope, status)

    def proposeMemory(req: ProposeMemoryRequest): Task[MemoryItem] =
      for {
        now <- Clock.instant
        id   = UUID.randomUUID().toString
        item = MemoryItem(
          id = id,
          personId = req.personId,
          scopeId = req.scopeId,
          status = MemoryStatus.Proposed,
          kind = req.kind,
          text = req.text,
          source = req.source,
          confidence = req.confidence,
          createdAt = now,
          updatedAt = now
        )
        _ <- memoryRepo.create(item)
        _ <- auditRepo.create(AuditEvent(
          id = UUID.randomUUID().toString,
          actor = "agent",
          action = "memory.propose",
          targetType = "memory_item",
          targetId = Some(id),
          scopeId = req.scopeId,
          payloadJson = "{}",
          createdAt = now
        ))
      } yield item

    def listMemory(personId: Option[String], scopeId: Option[String]): Task[List[MemoryItem]] =
      memoryRepo.findAll(personId, scopeId)

    def requestApproval(req: RequestApprovalRequest): Task[Approval] =
      for {
        now <- Clock.instant
        id   = UUID.randomUUID().toString
        approval = Approval(
          id = id,
          requestedBy = req.requestedBy,
          requiredPersonId = req.requiredPersonId,
          scopeId = req.scopeId,
          actionType = req.actionType,
          payloadJson = req.payloadJson,
          status = ApprovalStatus.Requested,
          createdAt = now,
          decidedAt = None
        )
        _ <- approvalRepo.create(approval)
        _ <- auditRepo.create(AuditEvent(
          id = UUID.randomUUID().toString,
          actor = req.requestedBy,
          action = "approval.request",
          targetType = "approval",
          targetId = Some(id),
          scopeId = req.scopeId,
          payloadJson = req.payloadJson,
          createdAt = now
        ))
      } yield approval

    def listApprovals(scopeId: Option[String], status: Option[String]): Task[List[Approval]] =
      approvalRepo.findAll(scopeId, status)
  }
}
